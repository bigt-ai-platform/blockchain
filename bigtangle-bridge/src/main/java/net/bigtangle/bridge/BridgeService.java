package net.bigtangle.bridge;

import java.util.ArrayList;
import java.util.List;

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
        PQKey vaultKey = PQKey.fromPublicOnly(Utils.HEX.decode(bridgeConfiguration.getVaultPubKeyHex()));
        return Address.fromHash160(networkParameters, Utils.sha256hash160(vaultKey.getPubKey()));
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

        // The output must pay the vault address.
        Address vault = vaultAddress();
        Address payTo;
        try {
            payTo = Address.fromHash160(networkParameters, tx.getOutput(0).getScriptPubKey().getPubKeyHash());
        } catch (Exception e) {
            throw new IllegalArgumentException("peg-in output is not payable to a standard address");
        }
        if (!payTo.toBase58().equals(vault.toBase58())) {
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
        if (burn.getAmount() > vault.getAmount()) {
            logger.warn("Peg-out burn amount {} exceeds vault amount {} for {}",
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
        vaultUtxo.setValue(amount);
        vaultUtxo.setTokenid(burn.getTokenIdHex());
        vaultUtxo.setAddress(vaultAddress().toBase58());
        vaultUtxo.setScript(ScriptBuilder.createOutputScript(vaultAddress()));
        vaultUtxo.setConfirmed(true);
        vaultUtxo.setSpent(false);

        Block releaseBlock = cacheBlockPrototypeService.getBlockPrototype(store);
        releaseBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.addInput(pegInBlockHash, new FreeStandingTransactionOutput(networkParameters, vaultUtxo));
        tx.addOutput(amount, recipient);

        // The release input must be signed by the vault private key: the vault
        // output is a P2PKH to the vault address, and CROSSTANGLE blocks are now
        // consensus-validated (scriptSig ownership proof) before they are saved.
        String vaultPriKeyHex = bridgeConfiguration.getVaultPriKeyHex();
        if (vaultPriKeyHex == null || vaultPriKeyHex.isEmpty()) {
            logger.warn("Peg-out requires bridge.vaultPriKeyHex to sign the release; skipping");
            return;
        }
        PQKey vaultKey = PQKey.fromPrivateKeyHex(vaultPriKeyHex);
        Script scriptPubKey = ScriptBuilder.createOutputScript(vaultAddress());
        Sha256Hash sighash = tx.hashForSignature(0, scriptPubKey.getProgram(), Transaction.SigHash.ALL, false);
        tx.getInput(0).setScriptSig(ScriptBuilder.createInputScriptForPQ(vaultKey.sign(sighash), vaultKey));
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

                Block remoteBlock = getRemoteBlock(l0Url, output.getBlockHashHex());
                byte[] toAddressInSubtangle = remoteBlock.getTransactions().get(0).getToAddressInSubtangle();
                if (toAddressInSubtangle == null) {
                    continue;
                }

                // Mint wrapped tokens EXACTLY ONCE, to the locked beneficiary.
                // The value is backed 1:1 by the observed L0 vault lock — never
                // minted twice (a second mint to the vault key would over-issue
                // 2x collateral).
                Address address = Address.fromHash160(networkParameters, toAddressInSubtangle);
                issueWrappedTokens(vaultKey, address, output.getValue(), store);

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

    private void issueWrappedTokens(PQKey signKey, Address address, Coin amount,
            BlockStoreInterface store) throws Exception {
        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.addOutput(amount, address);
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
        for (UTXO utxo : r.getOutputs()) {
            if (utxo.getValue().getValue().signum() > 0) {
                list.add(utxo);
            }
        }
        return list;
    }

    private Block getRemoteBlock(String l0Url, String blockHashHex) throws Exception {
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        params.put("hashHex", blockHashHex);
        byte[] data = OkHttp3Util.postAndGetBlock(l0Url + "/" + ReqCmd.getBlockByHash.name(),
                Json.jsonmapper().writeValueAsString(params));
        return networkParameters.getDefaultSerializer().makeBlock(data);
    }
}
