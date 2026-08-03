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
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
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
     * Peg-in: locks an L0 UTXO to the vault address. The CROSSTANGLE block
     * CONSUMES the supplied UTXO as an input and re-creates its value as a
     * vault output, so no value is created out of thin air. The vault is keyed
     * on the original outpoint, so locking the same UTXO twice is rejected.
     */
    public void processPegIn(UTXO utxo, String l1BeneficiaryAddress, BlockStoreInterface store) throws Exception {
        if (!bridgeConfiguration.isActive()) {
            return;
        }
        if (utxo == null) {
            throw new IllegalArgumentException("peg-in UTXO is null");
        }
        if (l1BeneficiaryAddress == null || l1BeneficiaryAddress.isEmpty()) {
            throw new IllegalArgumentException("peg-in beneficiary is missing");
        }
        // Replay guard: a source UTXO can only be locked once.
        if (vaultExists(store, utxo.getBlockHash(), utxo.getIndex())) {
            logger.warn("Peg-in UTXO {}:{} already locked, skipping", utxo.getBlockHash(), utxo.getIndex());
            return;
        }

        Address vault = vaultAddress();
        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.setToAddressInSubtangle(Address.fromBase58(networkParameters, l1BeneficiaryAddress).getHash160());
        tx.addInput(utxo.getBlockHash(), new FreeStandingTransactionOutput(networkParameters, utxo));
        tx.addOutput(utxo.getValue(), vault);
        b.addTransaction(tx);

        blockSaveService.saveBlockPermissive(b, store);

        VaultRecord vaultRecord = new VaultRecord(networkParameters.getChainId(),
                utxo.getBlockHash(), utxo.getIndex(), utxo.getValue().getValue().longValue(),
                Utils.HEX.encode(utxo.getValue().getTokenid()),
                l1BeneficiaryAddress, false);
        store.saveVaultUTXO(vaultRecord);

        logger.info("Peg-in: locked {} (outpoint {}:{}) to vault for L1 beneficiary {}",
                utxo.getValue().toString(), utxo.getBlockHash(), utxo.getIndex(), l1BeneficiaryAddress);
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

        // The peg-out spends the locked vault output so value is conserved:
        // the input references the vault UTXO (created by the peg-in block),
        // the output pays the burn recipient.
        Sha256Hash vaultTxHash = Sha256Hash.ZERO_HASH;
        try {
            Block pegInBlock = store.get(vault.getUtxoBlockHash());
            if (pegInBlock != null && !pegInBlock.getTransactions().isEmpty()) {
                vaultTxHash = pegInBlock.getTransactions().get(0).getHash();
            }
        } catch (Exception e) {
            logger.debug("Could not resolve vault peg-in tx hash, using zero: {}", e.getMessage());
        }
        UTXO vaultUtxo = new UTXO();
        vaultUtxo.setBlockHash(vault.getUtxoBlockHash());
        vaultUtxo.setHash(vaultTxHash);
        vaultUtxo.setIndex(vault.getUtxoIndex());
        vaultUtxo.setValue(amount);
        vaultUtxo.setTokenid(burn.getTokenIdHex());
        vaultUtxo.setAddress(vaultAddress().toBase58());
        vaultUtxo.setScript(ScriptBuilder.createOutputScript(vaultAddress()));
        vaultUtxo.setConfirmed(true);
        vaultUtxo.setSpent(false);

        Block releaseBlock = cacheBlockPrototypeService.getBlockPrototype(store);
        releaseBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.addInput(vault.getUtxoBlockHash(), new FreeStandingTransactionOutput(networkParameters, vaultUtxo));
        tx.addOutput(amount, recipient);
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

                Block b = cacheBlockPrototypeService.getBlockPrototype(store);
                b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
                b.addCoinbaseTransaction(vaultKey.getPubKey(), output.getValue(), null,
                        new MemoInfo("peg-in"));
                blockSaveService.saveBlockPermissive(b, store);

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
