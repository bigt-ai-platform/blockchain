package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import net.bigtangle.bridge.AnchorConfiguration;
import net.bigtangle.bridge.AnchorService;
import net.bigtangle.bridge.BridgeConfiguration;
import net.bigtangle.bridge.BridgeService;
import net.bigtangle.bridge.LayerAnchor;
import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MerkleProof;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.data.VaultRecord;

public class BridgeServiceTest extends AbstractIntegrationTest {

    @Autowired
    private BridgeService bridgeService;

    @Autowired
    private AnchorService anchorService;

    @Autowired
    private AnchorConfiguration anchorConfiguration;

    @Autowired
    private BridgeConfiguration bridgeConfiguration;

    @Value("${local.server.port}")
    private int port;

    private static final String L1_CHAIN_ID = "ordermatch";

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(testPub);
        anchorConfiguration.setPriKeyHex(testPriv);
        anchorConfiguration.setL0Url("http://localhost:" + port + "/");
        bridgeConfiguration.setActive(true);
        bridgeConfiguration.setVaultPubKeyHex(testPub);
        bridgeConfiguration.setVaultPriKeyHex(testPriv);
    }

    @Test
    public void testVaultRecordSaveAndQuery() throws Exception {
        VaultRecord v = new VaultRecord(L1_CHAIN_ID, Sha256Hash.ZERO_HASH,
                0, 100000,
                Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID),
                "test_addr", false);
        store.saveVaultUTXO(v);

        List<VaultRecord> vaults = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertEquals(1, vaults.size());
        assertEquals(100000, vaults.get(0).getAmount());
        assertFalse(vaults.get(0).isSpent());
    }

    @Test
    public void testVaultRecordMarkSpent() throws Exception {
        VaultRecord v = new VaultRecord(L1_CHAIN_ID, Sha256Hash.ZERO_HASH,
                0, 50000,
                Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID),
                "test_addr", false);
        store.saveVaultUTXO(v);
        store.markVaultUTXOSpent(L1_CHAIN_ID, Sha256Hash.ZERO_HASH, 0);

        List<VaultRecord> spent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, true);
        assertFalse(spent.isEmpty());
        assertTrue(spent.get(0).isSpent());
    }

    @Test
    public void testAnchorSaveAndQuery() throws Exception {
        AnchorRecord r = new AnchorRecord();
        r.setChainId(L1_CHAIN_ID);
        r.setL1RewardHeadHash(Sha256Hash.ZERO_HASH);
        r.setL1Height(1);
        r.setBlockHash(Sha256Hash.ZERO_HASH);
        r.setConfirmed(false);
        store.saveAnchor(r);

        AnchorRecord saved = store.getLatestAnchorByChainId(L1_CHAIN_ID);
        assertNotNull(saved);
        assertEquals(L1_CHAIN_ID, saved.getChainId());
        assertFalse(saved.isConfirmed());

        AnchorRecord byHash = store.getAnchorByBlockHash(Sha256Hash.ZERO_HASH);
        assertNotNull(byHash);
        assertEquals(L1_CHAIN_ID, byHash.getChainId());
    }

    @Test
    public void testAnchorWithSpvProof() throws Exception {
        Block tipProto = cacheBlockPrototypeService.getBlockPrototype(store);
        List<Sha256Hash> blockHashes = new ArrayList<>();
        blockHashes.add(tipProto.getHash());
        blockHashes.add(tipProto.getPrevBlockHash());
        Sha256Hash root = MerkleProof.computeRoot(blockHashes);
        MerkleProof proof = MerkleProof.buildProofFor(blockHashes, tipProto.getHash());

        PQKey signKey = PQKey.createNew();
        byte[] sigBytes = signKey.sign(tipProto.getHash()).encodeToDER();

        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, tipProto.getHash(),
                1, root, sigBytes, proof);
        anchorService.validateAndSaveAnchor(anchor, tipProto.getHash(), store);

        AnchorRecord saved = store.getAnchorByBlockHash(tipProto.getHash());
        assertNotNull(saved);
        assertNotNull(saved.getConfirmedRoot());
        assertEquals(root, saved.getConfirmedRoot());
    }

    @Test
    public void testPegOutSkippedForUnconfirmedAnchor() throws Exception {
        VaultRecord vault = new VaultRecord(L1_CHAIN_ID, Sha256Hash.ZERO_HASH,
                0, 100000,
                Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID),
                "addr", false);
        store.saveVaultUTXO(vault);

        AnchorRecord unconfirmed = new AnchorRecord();
        unconfirmed.setChainId(L1_CHAIN_ID);
        unconfirmed.setL1RewardHeadHash(Sha256Hash.ZERO_HASH);
        unconfirmed.setL1Height(1);
        unconfirmed.setBlockHash(Sha256Hash.ZERO_HASH);
        unconfirmed.setConfirmed(false);
        store.saveAnchor(unconfirmed);

        bridgeService.processPegOut(unconfirmed, store);

        List<VaultRecord> vaults = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertFalse(vaults.isEmpty());
        assertFalse(vaults.get(0).isSpent());
    }

    @Test
    public void testPegOutSkippedForNoSpvProof() throws Exception {
        VaultRecord vault = new VaultRecord(L1_CHAIN_ID, Sha256Hash.ZERO_HASH,
                0, 100000,
                Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID),
                "addr", false);
        store.saveVaultUTXO(vault);

        AnchorRecord noSpv = new AnchorRecord();
        noSpv.setChainId(L1_CHAIN_ID);
        noSpv.setL1RewardHeadHash(Sha256Hash.ZERO_HASH);
        noSpv.setL1Height(1);
        noSpv.setBlockHash(Sha256Hash.ZERO_HASH);
        noSpv.setConfirmed(true);
        store.saveAnchor(noSpv);

        bridgeService.processPegOut(noSpv, store);

        List<VaultRecord> vaults = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertFalse(vaults.isEmpty());
        assertFalse(vaults.get(0).isSpent());
    }

    @Test
    public void testAnchorRecordsByChainId() throws Exception {
        AnchorRecord r = new AnchorRecord();
        r.setChainId(L1_CHAIN_ID);
        r.setL1RewardHeadHash(Sha256Hash.ZERO_HASH);
        r.setL1Height(1);
        r.setBlockHash(Sha256Hash.ZERO_HASH);
        r.setConfirmed(false);
        store.saveAnchor(r);

        List<AnchorRecord> anchors = store.getAnchorsByChainId(L1_CHAIN_ID, 0, 100);
        assertEquals(1, anchors.size());
        assertEquals(L1_CHAIN_ID, anchors.get(0).getChainId());
    }
}
