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
import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.crypto.pq.SignatureBundle;
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

    private PQKey testKey;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        testKey = PQKey.createNew();
        String pubKeyHex = Utils.HEX.encode(testKey.getPublicKeyBytes());

        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(pubKeyHex);
        anchorConfiguration.setL0Url("http://localhost:" + port + "/");
        bridgeConfiguration.setActive(true);
        bridgeConfiguration.setVaultPubKeyHex(pubKeyHex);
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

        Sha256Hash l1Hash = tipProto.getHash();
        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":1",
                l1Hash, 1, root, null, proof, null);
        anchor.setSignature(anchor.sign(testKey).serialize());
        anchorService.validateAndSaveAnchor(anchor, tipProto.getHash(), store);

        AnchorRecord saved = store.getAnchorByBlockHash(tipProto.getHash());
        assertNotNull(saved);
        assertNotNull(saved.getConfirmedRoot());
        assertEquals(root, saved.getConfirmedRoot());
        assertNotNull(saved.getSpvProofHex());
    }

    @Test
    public void testPegOutReleasedWithBurn() throws Exception {
        Sha256Hash vaultBlockHash = Sha256Hash.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        long vaultIndex = 0;
        long amount = 100000;
        String tokenIdHex = Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID);
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();

        VaultRecord vault = new VaultRecord(L1_CHAIN_ID, vaultBlockHash,
                vaultIndex, amount, tokenIdHex, recipient, false);
        store.saveVaultUTXO(vault);

        // Build a signature- and SPV-valid anchor with an embedded burn for this vault.
        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        java.util.Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        LayerAnchor.AnchorBurn burn = new LayerAnchor.AnchorBurn(
                vaultBlockHash.toString() + ":" + vaultIndex, recipient, amount, tokenIdHex);
        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":5",
                head, 5, root, null, proof, burn);
        anchor.setSignature(anchor.sign(testKey).serialize());

        anchorService.validateAndSaveAnchor(anchor, head, store);
        store.updateAnchorConfirmed(L1_CHAIN_ID, 5, true);

        AnchorRecord confirmed = store.getAnchorByChainIdAndHeight(L1_CHAIN_ID, 5);
        assertNotNull(confirmed);
        assertTrue(confirmed.isConfirmed());

        bridgeService.processPegOut(confirmed, store);

        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertTrue(unspent.isEmpty(), "Vault must be released and marked spent after peg-out with burn");
        List<VaultRecord> spent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, true);
        assertEquals(1, spent.size());
        assertTrue(spent.get(0).isSpent());
    }

    @Test
    public void testPegOutRejectsMismatchedBurnAmount() throws Exception {
        Sha256Hash vaultBlockHash = Sha256Hash.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        long vaultIndex = 0;
        long amount = 100000;
        String tokenIdHex = Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID);
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();

        VaultRecord vault = new VaultRecord(L1_CHAIN_ID, vaultBlockHash,
                vaultIndex, amount, tokenIdHex, recipient, false);
        store.saveVaultUTXO(vault);

        Sha256Hash head = Sha256Hash.wrap("2222222222222222222222222222222222222222222222222222222222222222");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        java.util.Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        // Burn requests MORE than the vault holds -> must not release.
        LayerAnchor.AnchorBurn burn = new LayerAnchor.AnchorBurn(
                vaultBlockHash.toString() + ":" + vaultIndex, recipient, amount * 2, tokenIdHex);
        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":6",
                head, 6, root, null, proof, burn);
        anchor.setSignature(anchor.sign(testKey).serialize());

        anchorService.validateAndSaveAnchor(anchor, head, store);
        store.updateAnchorConfirmed(L1_CHAIN_ID, 6, true);

        bridgeService.processPegOut(store.getAnchorByChainIdAndHeight(L1_CHAIN_ID, 6), store);

        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertEquals(1, unspent.size());
        assertFalse(unspent.get(0).isSpent(), "Over-amount burn must not release the vault");
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
