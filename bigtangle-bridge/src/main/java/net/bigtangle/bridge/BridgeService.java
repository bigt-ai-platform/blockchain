package net.bigtangle.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.data.VaultRecord;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

/**
 * Bridge service for bidirectional peg between L0 and L1.
 *
 * <p>Peg-in locks an L0 UTXO to the vault address and records a
 * {@link VaultRecord} keyed on the original UTXO outpoint (replay-safe). A
 * peg-out is ONLY honoured when a signature- and SPV-verified anchor embeds a
 * {@link LayerAnchor.AnchorBurn} referencing that exact vault, recipient and
 * amount; the release block spends the locked value and the vault is marked
 * spent. Wrapped L1 issuance is backed by observed, not-yet-issued vault locks.
 */
@Service
public class BridgeService {

    private static final Logger logger = LoggerFactory.getLogger(BridgeService.class);

    @Autowired
    private BridgeConfiguration bridgeConfiguration;

    @Autowired
    private AnchorConfiguration anchorConfiguration;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private BlockSaveService blockSaveService;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private CacheBlockPrototypeService cacheBlockPrototypeService;

    @Autowired
    private ObjectMapper jsonmapper;

    private Address vaultAddress() {
        if (isMultisigVault()) {
            return Address.fromP2SHScript(networkParameters, vaultScript());
        }
        PQKey vaultKey = PQKey.fromPublicOnly(Utils.HEX.decode(bridgeConfiguration.getVaultPubKeyHex()));
        return Address.fromHash160(networkParameters, Utils.sha256hash160(vaultKey.getPubKey()));
    }

    /**
     * True when the vault is configured as an M-of-N multisig
     * ({@code bridge.vaultPubKeyHexList} non-empty). Falls back to the legacy
     * single-key vault otherwise.
     */
    private boolean isMultisigVault() {
        return bridgeConfiguration.getVaultPubKeyHexList() != null
                && !bridgeConfiguration.getVaultPubKeyHexList().isEmpty();
    }

    /** The sorted M-of-N redeem script ({@code M <pk...> N OP_CHECKMULTISIG}). */
    private Script vaultRedeemScript() {
        List<PQKey> pubkeys = new ArrayList<>();
        for (String hex : bridgeConfiguration.getVaultPubKeyHexList()) {
            pubkeys.add(PQKey.fromPublicOnly(Utils.HEX.decode(hex)));
        }
        return ScriptBuilder.createRedeemScript(bridgeConfiguration.getVaultM(), pubkeys);
    }

    /**
     * The script that locks vault funds: P2SH over the M-of-N redeem script
     * (multisig mode) or a P2PKH to the single vault key (legacy mode). Every
     * peg-in must pay this script and the peg-out release spends it, so the
     * script is the single source of truth for what "the vault" is.
     */
    private Script vaultScript() {
        if (isMultisigVault()) {
            return ScriptBuilder.createP2SHOutputScript(vaultRedeemScript());
        }
        return ScriptBuilder.createOutputScript(vaultAddress());
    }

    /**
     * Signs the first input of {@code tx} so it spends the vault script.
     * Returns false (and logs) when the configured keys cannot satisfy the
     * vault script, so {@code processPegOut} can skip the release instead of
     * emitting an invalid CROSSTANGLE block.
     * <ul>
     * <li>Legacy (single-key) mode: a P2PKH scriptSig signed by
     *     {@code bridge.vaultPriKeyHex}.</li>
     * <li>M-of-N mode: a P2SH scriptSig with {@code vaultM} signatures over the
     *     redeem script, ordered by the signer's pubkey position in the sorted
     *     redeem script (OP_CHECKMULTISIG is order-sensitive). Requires the
     *     {@code vaultM} private keys in {@code bridge.vaultPriKeyHexList}.</li>
     * </ul>
     */
    private boolean signVaultRelease(Transaction tx) throws Exception {
        if (isMultisigVault()) {
            Script redeem = vaultRedeemScript();
            List<PQKey> sortedPubkeys = redeemPubKeysSorted();
            Map<String, PQKey> signerByPub = new HashMap<>();
            for (String hex : bridgeConfiguration.getVaultPriKeyHexList()) {
                PQKey key = PQKey.fromPrivateKeyHex(hex);
                signerByPub.put(Utils.HEX.encode(key.getPublicKeyBytes()), key);
            }
            List<byte[]> signatures = new ArrayList<>();
            int required = bridgeConfiguration.getVaultM();
            for (PQKey pub : sortedPubkeys) {
                if (signatures.size() >= required) {
                    break;
                }
                PQKey signer = signerByPub.get(Utils.HEX.encode(pub.getPublicKeyBytes()));
                if (signer == null) {
                    continue;
                }
                Sha256Hash sighash = tx.hashForSignature(0, redeem.getProgram(), Transaction.SigHash.ALL, false);
                signatures.add(signer.sign(sighash).serialize());
            }
            if (signatures.size() < required) {
                logger.warn("Peg-out M-of-N release requires {} signatures but only {} vault private keys "
                        + "matched the redeem script; skipping", required, signatures.size());
                return false;
            }
            tx.getInput(0).setScriptSig(
                    ScriptBuilder.createMultiSigInputScriptBytes(signatures, redeem.getProgram()));
            return true;
        }

        String vaultPriKeyHex = bridgeConfiguration.getVaultPriKeyHex();
        if (vaultPriKeyHex == null || vaultPriKeyHex.isEmpty()) {
            logger.warn("Peg-out requires bridge.vaultPriKeyHex to sign the release; skipping");
            return false;
        }
        PQKey vaultKey = PQKey.fromPrivateKeyHex(vaultPriKeyHex);
        Sha256Hash sighash = tx.hashForSignature(0, vaultScript().getProgram(), Transaction.SigHash.ALL, false);
        tx.getInput(0).setScriptSig(ScriptBuilder.createInputScriptForPQ(vaultKey.sign(sighash), vaultKey));
        return true;
    }

    /** The vault redeem-script pubkeys in their sorted (script) order. */
    private List<PQKey> redeemPubKeysSorted() {
        List<PQKey> pubkeys = new ArrayList<>();
        for (String hex : bridgeConfiguration.getVaultPubKeyHexList()) {
            pubkeys.add(PQKey.fromPublicOnly(Utils.HEX.decode(hex)));
        }
        pubkeys.sort(PQKey.PUBKEY_COMPARATOR);
        return pubkeys;
    }

    /**
     * Peg-in: locks an L0 UTXO to the vault address. The caller submits a
     * SIGNED transaction that spends the UTXO and pays the vault; the input
     * scriptSig is verified against the UTXO's scriptPubKey (only the UTXO's
     * owner can produce it) before anything is locked. The L1 beneficiary is
     * carried in the signed tx's toAddressInSubtangle, so it is covered by the
     * input signature and cannot be changed after signing. The CROSSTANGLE
     * block wraps this transaction; the vault is keyed on the original
     * outpoint, so locking the same UTXO twice is rejected.
     */
    public void processPegIn(Transaction tx, BlockStoreInterface store) throws Exception {
        if (!bridgeConfiguration.isActive()) {
            return;
        }
        if (tx == null || tx.getInputs().isEmpty() || tx.getOutputs().isEmpty()) {
            throw new IllegalArgumentException("peg-in transaction must have inputs and outputs");
        }
        if (tx.getInputs().size() != 1 || tx.getOutputs().size() != 1) {
            throw new IllegalArgumentException("peg-in transaction must have exactly one input and one output");
        }
        TransactionInput in = tx.getInput(0);
        UTXO utxo = store.getTransactionOutput(in.getOutpoint().getBlockHash(),
                in.getOutpoint().getTxHash(), in.getOutpoint().getIndex());
        if (utxo == null) {
            throw new IllegalArgumentException("peg-in input UTXO is not found on this chain");
        }
        if (utxo.getScript() == null || in.getScriptSig() == null) {
            throw new IllegalArgumentException("peg-in input UTXO/scriptSig missing; cannot prove ownership");
        }
        // OWNERSHIP PROOF: the input scriptSig must correctly spend the UTXO's
        // scriptPubKey — only the UTXO's owner can produce it, so an attacker
        // cannot lock a UTXO it does not own.
        in.getScriptSig().correctlySpends(tx, 0, utxo.getScript(), Script.ALL_VERIFY_FLAGS);

        // The output must pay the vault script (P2SH M-of-N or legacy P2PKH).
        // Comparing script programs directly avoids address-version ambiguity
        // between P2PKH and P2SH vault addresses.
        if (!java.util.Arrays.equals(tx.getOutput(0).getScriptPubKey().getProgram(),
                vaultScript().getProgram())) {
            throw new IllegalArgumentException("peg-in output must pay the vault address");
        }

        // Replay guard: a source UTXO can only be locked once.
        if (vaultExists(store, utxo.getBlockHash(), utxo.getIndex())) {
            logger.warn("Peg-in UTXO {}:{} already locked, skipping", utxo.getBlockHash(), utxo.getIndex());
            return;
        }

        // The L1 beneficiary travels in the signed tx and is covered by the
        // input signature, so it cannot be altered after signing.
        byte[] beneficiaryHash = tx.getToAddressInSubtangle();
        if (beneficiaryHash == null || beneficiaryHash.length == 0) {
            throw new IllegalArgumentException(
                    "peg-in transaction must declare the L1 beneficiary (toAddressInSubtangle)");
        }
        String l1BeneficiaryAddress = Address.fromHash160(networkParameters, beneficiaryHash).toBase58();

        // The L1 DESTINATION chain id keys the vault (the chain the wrapped
        // tokens live on). It must be declared in the signed transaction data,
        // so it is covered by the input signature and consistent with the
        // peg-out lookup (which uses the anchor's L1 chain id).
        String l1ChainId = pegInChainId(tx);
        if (l1ChainId == null || l1ChainId.isEmpty()) {
            throw new IllegalArgumentException(
                    "peg-in transaction must declare the L1 destination chain id (PegInInfo data)");
        }

        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        b.addTransaction(tx);

        blockSaveService.saveBlockPermissive(b, store);

        VaultRecord vaultRecord = new VaultRecord(l1ChainId,
                utxo.getBlockHash(), utxo.getIndex(), b.getHash(),
                utxo.getValue().getValue().longValue(),
                Utils.HEX.encode(utxo.getValue().getTokenid()),
                l1BeneficiaryAddress, false);
        store.saveVaultUTXO(vaultRecord);

        logger.info("Peg-in: locked {} (outpoint {}:{}) to vault on L1 chain {} for beneficiary {}",
                utxo.getValue().toString(), utxo.getBlockHash(), utxo.getIndex(), l1ChainId, l1BeneficiaryAddress);
    }

    /** The L1 destination chain id declared in the peg-in transaction's PegInInfo data, or null. */
    private String pegInChainId(Transaction tx) {
        try {
            if (tx.getData() == null) {
                return null;
            }
            String dataClassName = tx.getDataClassName();
            if (dataClassName != null && !dataClassName.isEmpty()
                    && !"PegInInfo".equals(dataClassName)) {
                return null;
            }
            java.util.Map<String, Object> info = Json.jsonmapper().readValue(tx.getData(), java.util.Map.class);
            Object chainId = info.get("chainId");
            return chainId != null ? chainId.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean vaultExists(BlockStoreInterface store, Sha256Hash blockHash, long index) throws Exception {
        List<VaultRecord> vaults = store.getVaultUTXOsByChainId(networkParameters.getChainId(), false);
        for (VaultRecord v : vaults) {
            if (v.getUtxoBlockHash().equals(blockHash) && v.getUtxoIndex() == index) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replay guard used by the L1 issuance poll: a vault outpoint counts as
     * already handled if it appears in EITHER the unspent (peg-in locked) or
     * the spent (already-issued) vault set, so a lock observed twice never
     * mints wrapped tokens twice.
     */
    private boolean vaultOutpointRecorded(BlockStoreInterface store, Sha256Hash blockHash, long index) throws Exception {
        for (boolean spent : new boolean[] { false, true }) {
            List<VaultRecord> vaults = store.getVaultUTXOsByChainId(networkParameters.getChainId(), spent);
            for (VaultRecord v : vaults) {
                if (v.getUtxoBlockHash().equals(blockHash) && v.getUtxoIndex() == index) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Peg-out: releases exactly the vault referenced by the anchor's embedded,
     * signature-covered burn. The anchor must be confirmed and carry a valid
     * SPV proof (validated before the record exists). The release block spends
     * the locked value and pays it to the burn recipient; the vault is then
     * marked spent so the burn cannot be replayed.
     */
    public void processPegOut(AnchorRecord anchor, BlockStoreInterface store) throws Exception {
        if (!bridgeConfiguration.isActive()) {
            return;
        }
        if (anchor == null) {
            return;
        }
        if (!anchor.isConfirmed()) {
            logger.warn("Peg-out requires confirmed anchor, chain {} height {}",
                    anchor.getChainId(), anchor.getL1Height());
            return;
        }
        if (anchor.getBurnJson() == null || anchor.getBurnJson().isEmpty()) {
            logger.warn("Peg-out requires an embedded burn in the anchor, chain {} height {}",
                    anchor.getChainId(), anchor.getL1Height());
            return;
        }

        LayerAnchor.AnchorBurn burn = LayerAnchor.AnchorBurn.fromJson(anchor.getBurnJson());
        // The vault is keyed by the L1 DESTINATION chain id (the chain the
        // wrapped tokens live on) — the peg-in saved it under that id, so the
        // lookup here uses the anchor's L1 chain id, not the node's own.
        VaultRecord vault = findVault(anchor.getChainId(), burn.getVaultRef(), store);
        if (vault == null) {
            logger.warn("Peg-out burn references unknown or already released vault {}", burn.getVaultRef());
            return;
        }
        if (vault.isSpent()) {
            logger.warn("Peg-out vault {} already released", burn.getVaultRef());
            return;
        }
        // R5: peg-out is ALL-OR-NOTHING per vault. A partial burn is rejected:
        // returning the remainder as a change output would strand it (the vault
        // record is marked spent, so the change UTXO would have no unspent
        // VaultRecord and could never be released).
        if (burn.getAmount() != vault.getAmount()) {
            logger.warn("Peg-out burn amount {} does not match vault amount {} for {} (partial burns rejected)",
                    burn.getAmount(), vault.getAmount(), burn.getVaultRef());
            return;
        }
        if (!burn.getTokenIdHex().equals(vault.getTokenIdHex())) {
            logger.warn("Peg-out burn token {} does not match vault token {}",
                    burn.getTokenIdHex(), vault.getTokenIdHex());
            return;
        }

        Address recipient = Address.fromBase58(networkParameters, burn.getRecipient());
        Coin amount = Coin.valueOf(burn.getAmount(), Utils.HEX.decode(burn.getTokenIdHex()));

        // The release spends the ACTUAL vault OUTPUT created by the peg-in block
        // (pegInBlockHash, pegInTxHash, index 0) — not the original consumed
        // source outpoint, which is already spent and would be consensus-invalid.
        Sha256Hash pegInBlockHash = vault.getPegInBlockHash() != null
                ? vault.getPegInBlockHash() : vault.getUtxoBlockHash();
        Sha256Hash vaultTxHash = Sha256Hash.ZERO_HASH;
        try {
            Block pegInBlock = store.get(pegInBlockHash);
            if (pegInBlock != null && !pegInBlock.getTransactions().isEmpty()) {
                vaultTxHash = pegInBlock.getTransactions().get(0).getHash();
            }
        } catch (Exception e) {
            logger.debug("Could not resolve vault peg-in tx hash, using zero: {}", e.getMessage());
        }
        UTXO vaultUtxo = new UTXO();
        vaultUtxo.setBlockHash(pegInBlockHash);
        vaultUtxo.setHash(vaultTxHash);
        vaultUtxo.setIndex(0); // the peg-in tx's single vault output
        vaultUtxo.setValue(Coin.valueOf(vault.getAmount(), Utils.HEX.decode(burn.getTokenIdHex())));
        vaultUtxo.setTokenid(burn.getTokenIdHex());
        vaultUtxo.setAddress(vaultAddress().toBase58());
        vaultUtxo.setScript(vaultScript());
        vaultUtxo.setConfirmed(true);
        vaultUtxo.setSpent(false);

        Block releaseBlock = cacheBlockPrototypeService.getBlockPrototype(store);
        releaseBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.addInput(pegInBlockHash, new FreeStandingTransactionOutput(networkParameters, vaultUtxo));
        tx.addOutput(amount, recipient);

        // The release input must prove ownership of the vault script: a legacy
        // P2PKH release is signed by the single vault private key; an M-of-N
        // release carries `vaultM` ordered signatures plus the redeem script
        // (P2SH). CROSSTANGLE blocks are consensus-validated (scriptSig
        // ownership proof) before they are saved, so a release that does not
        // satisfy the vault script is rejected by L0.
        if (!signVaultRelease(tx)) {
            logger.warn("Peg-out: vault release not signed (vault {}), skipping", burn.getVaultRef());
            return;
        }
        releaseBlock.addTransaction(tx);

        blockSaveService.saveBlockPermissive(releaseBlock, store);
        store.markVaultUTXOSpent(vault.getChainId(), vault.getUtxoBlockHash(), vault.getUtxoIndex());

        logger.info("Peg-out: released {} to {} for vault {} (burn from anchor {}:{})",
                amount.toString(), burn.getRecipient(), burn.getVaultRef(),
                anchor.getChainId(), anchor.getL1Height());
    }

    private VaultRecord findVault(String chainId, String vaultRef, BlockStoreInterface store) throws Exception {
        String[] parts = vaultRef.split(":");
        if (parts.length != 2) {
            return null;
        }
        Sha256Hash blockHash = Sha256Hash.wrap(parts[0]);
        long index = Long.parseLong(parts[1]);
        List<VaultRecord> vaults = store.getVaultUTXOsByChainId(chainId, false);
        for (VaultRecord v : vaults) {
            if (v.getUtxoBlockHash().equals(blockHash) && v.getUtxoIndex() == index) {
                return v;
            }
        }
        return null;
    }

    /**
     * Called on L1: observes L0 vault locks for this chain and issues wrapped
     * tokens to the beneficiaries, skipping locks that were already issued
     * (replay protection). Queries L0 with the CONFIGURED vault address, not a
     * fresh random key.
     *
     * <p>Issuance is authenticated (N1), destination-chain-scoped (N2) and
     * bound to a hash-verified, confirmed L0 vault lock (N3):
     * <ul>
     * <li>the locking block returned by L0 is hash-verified against the
     *     requested hash, and the vault UTXO must be created by that block's
     *     transaction,</li>
     * <li>the lock transaction must actually pay the configured vault address
     *     for the same value,</li>
     * <li>the lock must declare THIS chain as its L1 destination
     *     (PegInInfo.chainId) — otherwise one L0 deposit would mint wrapped
     *     tokens on every watching L1 chain,</li>
     * <li>the wrapped mint itself is a signed, chain-scoped issuance that every
     *     L1 node re-validates via {@link L1CrosstangleHandler}.</li>
     * </ul>
     *
     * <p>Residual (N3-residual): the L0 "confirmed" flag on a vault UTXO is
     * ENDPOINT-CLAIMED (L0's MCMC confirmation, not Casper finality). The
     * fabrication vectors above are closed (hash commitment, vault-payment
     * binding, declared chain id); binding the mint to an L0-FINALIZED anchor
     * or a confirmation-depth threshold is left open as a known limitation.
     */
    public void processPegInFromL0(BlockStoreInterface store) throws Exception {
        if (!bridgeConfiguration.isActive()) {
            return;
        }
        String l0Url = anchorConfiguration.getL0Url();
        if (l0Url == null || l0Url.isEmpty()) {
            return;
        }
        if (bridgeConfiguration.getVaultPubKeyHex() == null || bridgeConfiguration.getVaultPubKeyHex().isEmpty()) {
            logger.warn("bridge.vaultPubKeyHex not configured; cannot observe L0 vault locks");
            return;
        }

        PQKey vaultKey = PQKey.fromPublicOnly(Utils.HEX.decode(bridgeConfiguration.getVaultPubKeyHex()));
        List<UTXO> outputs = getRemoteBalances(l0Url, vaultKey);
        for (UTXO output : outputs) {
            try {
                if (vaultOutpointRecorded(store, output.getBlockHash(), output.getIndex())) {
                    logger.debug("Vault lock {}:{} already issued, skipping", output.getBlockHash(), output.getIndex());
                    continue;
                }
                if (!output.isConfirmed()) {
                    logger.debug("Vault lock {}:{} not yet confirmed on L0, skipping",
                            output.getBlockHash(), output.getIndex());
                    continue;
                }

                // N3: fetch the locking block and verify its hash matches the
                // requested one — the block hash is a commitment to its content,
                // so a MITM/compromised L0 cannot return a fabricated block.
                Block remoteBlock = getRemoteBlock(l0Url, output.getBlockHashHex());
                if (remoteBlock.getTransactions() == null || remoteBlock.getTransactions().isEmpty()) {
                    continue;
                }
                Transaction lockTx = remoteBlock.getTransactions().get(0);
                // Bind the UTXO to the fetched block: the vault output must come
                // from THIS block's transaction.
                if (lockTx.getHash() == null || !lockTx.getHash().equals(output.getTxHash())) {
                    logger.warn("Vault UTXO {}:{} does not match the locking block's transaction, skipping",
                            output.getBlockHash(), output.getIndex());
                    continue;
                }
                // The lock tx must ACTUALLY pay the configured vault address for
                // the same value — a fabricated balance listing cannot name an
                // arbitrary L0 block as a vault lock.
                Coin vaultValue = vaultPaymentValue(lockTx);
                if (vaultValue == null || !vaultValue.equals(output.getValue())) {
                    logger.warn("Locking block {}:{} does not pay the vault (or value mismatch), skipping",
                            output.getBlockHash(), output.getIndex());
                    continue;
                }

                // N2: the peg-in must declare THIS chain as its L1 destination;
                // otherwise one L0 deposit would mint wrapped tokens on every
                // watching L1 chain (1:N collateral multiplication).
                String declaredChain = pegInChainId(lockTx);
                if (declaredChain == null || !declaredChain.equals(networkParameters.getChainId())) {
                    logger.debug("Vault lock {}:{} declares chain {} (this chain is {}), skipping",
                            output.getBlockHash(), output.getIndex(), declaredChain,
                            networkParameters.getChainId());
                    continue;
                }

                byte[] toAddressInSubtangle = lockTx.getToAddressInSubtangle();
                if (toAddressInSubtangle == null) {
                    continue;
                }

                // Mint wrapped tokens EXACTLY ONCE, to the locked beneficiary.
                // The value is backed 1:1 by the observed L0 vault lock — never
                // minted twice (a second mint to the vault key would over-issue
                // 2x collateral).
                Address address = Address.fromHash160(networkParameters, toAddressInSubtangle);
                issueWrappedTokens(address, output.getValue(),
                        output.getBlockHash(), output.getIndex(), store);

                // Record the issued lock so a later poll cannot mint again.
                VaultRecord issued = new VaultRecord(networkParameters.getChainId(),
                        output.getBlockHash(), output.getIndex(), output.getValue().getValue().longValue(),
                        Utils.HEX.encode(output.getValue().getTokenid()),
                        address.toBase58(), true);
                store.saveVaultUTXO(issued);

                logger.info("Peg-in from L0: issued wrapped {} to {}",
                        output.getValue().toString(), address);
            } catch (Exception e) {
                logger.warn("Failed to process peg-in from L0", e);
            }
        }
    }

    /**
     * The value the lock transaction pays to the configured vault address, or
     * null when it does not pay the vault at all.
     */
    private Coin vaultPaymentValue(Transaction lockTx) {
        byte[] vaultProgram = vaultScript().getProgram();
        for (TransactionOutput out : lockTx.getOutputs()) {
            if (java.util.Arrays.equals(out.getScriptPubKey().getProgram(), vaultProgram)) {
                return out.getValue();
            }
        }
        return null;
    }

    /**
     * Issues the wrapped tokens as an AUTHENTICATED bridge issuance: a
     * zero-input CROSSTANGLE mint declaring this chain and the L0 lock
     * reference, signed by the chain's DEDICATED issuance key (R4 — the vault
     * key stays on L0 and is never used to sign issuance). Every L1 node
     * verifies the signature (and chain id) via {@link L1CrosstangleHandler}
     * before the block is accepted, and the lock is replay-guarded at
     * confirmation (R3).
     */
    private void issueWrappedTokens(Address address, Coin amount, Sha256Hash lockBlockHash, long lockIndex,
            BlockStoreInterface store) throws Exception {
        String issuancePriKeyHex = bridgeConfiguration.getIssuancePriKeyHex();
        if (issuancePriKeyHex == null || issuancePriKeyHex.isEmpty()) {
            logger.warn("bridge.issuancePriKeyHex not configured; cannot sign wrapped issuance; skipping");
            return;
        }
        PQKey issuanceKey = PQKey.fromPrivateKeyHex(issuancePriKeyHex);
        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(L1CrosstangleHandler.ISSUE_WRAPPED_TOKEN_DATA_CLASS);
        tx.setData(Json.jsonmapper().writeValueAsBytes(java.util.Map.of(
                "chainId", networkParameters.getChainId(),
                "lockBlockHash", lockBlockHash.toString(),
                "lockIndex", lockIndex)));
        tx.addOutput(amount, address);
        // The signature covers the outputs (amount + recipient), the declared
        // chain id and the L0 lock reference — tx.getHash() excludes the
        // dataSignature field, so it can be set after signing.
        tx.setDataSignature(issuanceKey.sign(tx.getHash()).serialize());
        b.addTransaction(tx);
        blockSaveService.saveBlockPermissive(b, store);
    }

    private List<UTXO> getRemoteBalances(String l0Url, PQKey vaultKey) throws Exception {
        List<String> keyStrHex = new ArrayList<>();
        keyStrHex.add(Utils.HEX.encode(Utils.sha256hash160(vaultKey.getPubKey())));
        byte[] response = OkHttp3Util.post(l0Url + "/" + ReqCmd.getBalances.name(),
                Json.jsonmapper().writeValueAsString(keyStrHex).getBytes());
        GetBalancesResponse r = jsonmapper.readValue(response, GetBalancesResponse.class);
        List<UTXO> list = new ArrayList<>();
        if (r != null && r.getOutputs() != null) {
            for (UTXO utxo : r.getOutputs()) {
                if (utxo != null && utxo.getValue() != null && utxo.getValue().getValue().signum() > 0) {
                    list.add(utxo);
                }
            }
        }
        return list;
    }

    /**
     * Fetches a block by hash and verifies that the returned block actually
     * hashes to the requested value — the block hash is a commitment to its
     * content, so a MITM/compromised L0 endpoint cannot substitute a different
     * block for the requested one.
     */
    private Block getRemoteBlock(String l0Url, String blockHashHex) throws Exception {
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        params.put("hashHex", blockHashHex);
        byte[] data = OkHttp3Util.postAndGetBlock(l0Url + "/" + ReqCmd.getBlockByHash.name(),
                Json.jsonmapper().writeValueAsString(params));
        Block block = networkParameters.getDefaultSerializer().makeBlock(data);
        if (block == null || block.getHash() == null
                || !block.getHashAsString().equalsIgnoreCase(blockHashHex)) {
            throw new BlockStoreException(
                    "L0 returned a block whose hash does not match the requested " + blockHashHex);
        }
        return block;
    }
}
