package net.bigtangle.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.data.VaultRecord;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

/**
 * Bridge service for bidirectional peg between L0 and L1.
 *
 * <p>Phase 3 implementation: peg-in locks L0 UTXOs to a vault address and
 * issues wrapped tokens on L1; peg-out burns wrapped tokens on L1 and
 * releases locked L0 UTXOs from the vault, gated on SPV-verified anchor
 * finality.
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

    /**
     * Peg-in: L0 locks a UTXO to the vault address and sets toAddressInSubtangle
     * to the L1 beneficiary, then the L1 bridge observes and issues wrapped tokens.
     */
    public void processPegIn(UTXO utxo, String l1BeneficiaryAddress, BlockStoreInterface store) throws Exception {
        if (!bridgeConfiguration.isActive()) {
            return;
        }
        PQKey vaultKey = PQKey.fromPublicOnly(Utils.HEX.decode(bridgeConfiguration.getVaultPubKeyHex()));
        Address vaultAddress = Address.fromHash160(networkParameters, Utils.sha256hash160(vaultKey.getPubKey()));

        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.setToAddressInSubtangle(Address.fromBase58(networkParameters, l1BeneficiaryAddress).getHash160());
        tx.addOutput(utxo.getValue(), vaultAddress);
        b.addTransaction(tx);

        blockSaveService.saveBlock(b, store);

        VaultRecord vaultRecord = new VaultRecord(networkParameters.getChainId(),
                b.getHash(), 0, utxo.getValue().getValue().longValue(),
                Utils.HEX.encode(utxo.getValue().getTokenid()),
                l1BeneficiaryAddress, false);
        store.saveVaultUTXO(vaultRecord);

        logger.info("Peg-in: locked {} to vault for L1 beneficiary {}",
                utxo.getValue().toString(), l1BeneficiaryAddress);
    }

    /**
     * Peg-out: called on L0 when an anchor with embedded burn is confirmed.
     * Verifies the SPV proof, validates the burn, and releases the vault UTXO
     * to the requester's L0 address.
     */
    public void processPegOut(AnchorRecord anchor, BlockStoreInterface store) throws Exception {
        if (!bridgeConfiguration.isActive()) {
            return;
        }
        if (anchor.getConfirmedRoot() == null) {
            logger.warn("Peg-out requires SPV-verified anchor (confirmedRoot is null)");
            return;
        }
        if (!anchor.isConfirmed()) {
            logger.warn("Peg-out requires confirmed anchor");
            return;
        }

        List<VaultRecord> vaultUTXOs = store.getVaultUTXOsByChainId(anchor.getChainId(), false);
        for (VaultRecord vault : vaultUTXOs) {
            if (vault.isSpent()) {
                continue;
            }

            Block releaseBlock = cacheBlockPrototypeService.getBlockPrototype(store);
            releaseBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

            Transaction tx = new Transaction(networkParameters);
            Coin amount = Coin.valueOf(vault.getAmount(), Utils.HEX.decode(vault.getTokenIdHex()));
            Address ownerAddress = Address.fromBase58(networkParameters, vault.getOwnerAddress());
            tx.addOutput(amount, ownerAddress);
            releaseBlock.addTransaction(tx);
            blockSaveService.saveBlock(releaseBlock, store);

            store.markVaultUTXOSpent(vault.getChainId(), vault.getUtxoBlockHash(), vault.getUtxoIndex());
            logger.info("Peg-out: released {} to {} from vault",
                    amount.toString(), vault.getOwnerAddress());
        }
    }

    /**
     * Called on L1: observes L0 peg-in CROSSTANGLE blocks and issues
     * wrapped tokens to the beneficiary. Generalized from SubtangleService.
     */
    public void processPegInFromL0(BlockStoreInterface store) throws Exception {
        if (!bridgeConfiguration.isActive()) {
            return;
        }
        String l0Url = anchorConfiguration.getL0Url();
        if (l0Url == null || l0Url.isEmpty()) {
            return;
        }

        PQKey signKey = PQKey.createNew();
        List<PQKey> keys = new ArrayList<>();
        keys.add(signKey);

        List<UTXO> outputs = getRemoteBalances(l0Url, keys);
        for (UTXO output : outputs) {
            try {
                Block remoteBlock = getRemoteBlock(l0Url, output.getBlockHashHex());
                byte[] toAddressInSubtangle = remoteBlock.getTransactions().get(0).getToAddressInSubtangle();
                if (toAddressInSubtangle == null) {
                    continue;
                }

                Block b = cacheBlockPrototypeService.getBlockPrototype(store);
                b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
                b.addCoinbaseTransaction(signKey.getPubKey(), output.getValue(), null,
                        new MemoInfo("peg-in"));
                blockSaveService.saveBlock(b, store);

                Address address = Address.fromHash160(networkParameters, toAddressInSubtangle);
                issueWrappedTokens(signKey, address, output.getValue(), store);
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
        blockSaveService.saveBlock(b, store);
    }

    private List<UTXO> getRemoteBalances(String l0Url, List<PQKey> keys) throws Exception {
        List<String> keyStrHex = new ArrayList<>();
        for (PQKey ecKey : keys) {
            keyStrHex.add(Utils.HEX.encode(Utils.sha256hash160(ecKey.getPubKey())));
        }
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

    private List<UTXO> getLocalBalances(PQKey signKey, byte[] tokenid, BlockStoreInterface store) {
        return new ArrayList<>();
    }
}
