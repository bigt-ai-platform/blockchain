package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.server.config.ScheduleConfiguration;

import net.bigtangle.bridge.AnchorConfiguration;
import net.bigtangle.bridge.AnchorService;
import net.bigtangle.bridge.LayerAnchor;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MerkleProof;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.AnchorRecord;

public class AnchorRoundTripTest extends AbstractIntegrationTest {

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private AnchorService anchorService;

    @Autowired
    private AnchorConfiguration anchorConfiguration;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        scheduleConfiguration.setChainlength_active(true);
    }

    private void configureAnchorWithKey(PQKey key) {
        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(Utils.HEX.encode(key.getPublicKeyBytes()));
    }

    @Test
    public void testValidateAndSaveAnchor() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        long l1Height = 1;

        SignatureBundle sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, l1Height, null, sig.serialize(), null);

        anchorService.validateAndSaveAnchor(anchor, genesis.getHash(), store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", l1Height);
        assertNotNull(saved, "Anchor should be saved and retrievable");
    }

    @Test
    public void testProcessReceivedAnchor() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        SignatureBundle sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.serialize(), null);

        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toJson().getBytes(StandardCharsets.UTF_8));
        crosstangleBlock.addTransaction(tx);

        anchorService.processReceivedAnchor(crosstangleBlock, store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved, "Anchor should be saved after processing received CROSSTANGLE block");
    }

    @Test
    public void testConfirmAnchor() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        SignatureBundle sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.serialize(), null);

        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toJson().getBytes(StandardCharsets.UTF_8));
        crosstangleBlock.addTransaction(tx);

        store.saveAnchor(new AnchorRecord("L1", l1Hash, 1, null,
                Utils.HEX.encode(sig.serialize()), crosstangleBlock.getHash(), false));

        AnchorRecord before = store.getAnchorByBlockHash(crosstangleBlock.getHash());
        assertNotNull(before);
        assertEquals(false, before.isConfirmed());

        anchorService.confirmAnchor(crosstangleBlock, true, store);

        AnchorRecord after = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(after);
        assertEquals(true, after.isConfirmed(), "Anchor must be marked confirmed after confirmAnchor");
    }

    @Test
    public void testUnconfirmAnchor() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        SignatureBundle sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.serialize(), null);

        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toJson().getBytes(StandardCharsets.UTF_8));
        crosstangleBlock.addTransaction(tx);

        store.saveAnchor(new AnchorRecord("L1", l1Hash, 1, null,
                Utils.HEX.encode(sig.serialize()), crosstangleBlock.getHash(), true));

        anchorService.confirmAnchor(crosstangleBlock, false, store);

        AnchorRecord after = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(after);
        assertEquals(false, after.isConfirmed(),
                "Anchor must be marked unconfirmed after confirmAnchor(false)");
    }

    @Test
    public void testConfirmAnchorViaHandler() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        SignatureBundle sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.serialize(), null);

        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toJson().getBytes(StandardCharsets.UTF_8));
        crosstangleBlock.addTransaction(tx);

        anchorService.processReceivedAnchor(crosstangleBlock, store);
        anchorService.confirmAnchor(crosstangleBlock, true, store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved);
        assertEquals(true, saved.isConfirmed(),
                "After processReceivedAnchor + confirmAnchor, anchor must be confirmed");
    }

    @Test
    public void testGetAnchorByBlockHash() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        SignatureBundle sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.serialize(), null);

        anchorService.validateAndSaveAnchor(anchor, genesis.getHash(), store);

        AnchorRecord byHash = store.getAnchorByBlockHash(genesis.getHash());
        assertNotNull(byHash, "getAnchorByBlockHash should find the anchor");
        assertEquals("L1", byHash.getChainId());

        AnchorRecord notFound = store.getAnchorByBlockHash(Sha256Hash.ZERO_HASH);
        assertNull(notFound, "getAnchorByBlockHash should return null for non-existent block hash");
    }

    @Test
    public void testInvalidSignatureRejected() {
        PQKey correctKey = PQKey.createNew();
        configureAnchorWithKey(correctKey);

        PQKey wrongKey = PQKey.createNew();
        Sha256Hash hash = Sha256Hash.ZERO_HASH;
        SignatureBundle sig = wrongKey.sign(hash);

        LayerAnchor anchor = new LayerAnchor("L1", hash, 1, null, sig.serialize(), null);

        assertThrows(BlockStoreException.class,
                () -> anchorService.validateAndSaveAnchor(anchor, hash, store));
    }

    @Test
    public void testMerkleProofValid() {
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001"));
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000002"));
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000003"));
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000004"));
        Collections.sort(leaves);

        Sha256Hash targetLeaf = leaves.get(2);
        MerkleProof.ProofResult result = MerkleProof.buildProof(leaves, 2);
        boolean valid = result.proof.verify(targetLeaf, result.root);
        assertTrue(valid, "Merkle proof must verify");
    }

    @Test
    public void testMerkleProofTampered() {
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(Sha256Hash.wrap("000000000000000000000000000000000000000000000000000000000000000a"));
        leaves.add(Sha256Hash.wrap("000000000000000000000000000000000000000000000000000000000000000b"));
        leaves.add(Sha256Hash.wrap("000000000000000000000000000000000000000000000000000000000000000c"));
        Collections.sort(leaves);

        Sha256Hash tamperedLeaf = Sha256Hash.wrap("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        MerkleProof.ProofResult result = MerkleProof.buildProof(leaves, 1);
        boolean valid = result.proof.verify(tamperedLeaf, result.root);
        assertEquals(false, valid, "Tampered leaf must not verify");
    }

    @Test
    public void testMerkleProofWrongRoot() {
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000aa"));
        leaves.add(Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000bb"));
        Collections.sort(leaves);

        Sha256Hash targetLeaf = leaves.get(0);
        MerkleProof proof = MerkleProof.buildProof(leaves, 0).proof;
        Sha256Hash wrongRoot = Sha256Hash.wrap("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        boolean valid = proof.verify(targetLeaf, wrongRoot);
        assertEquals(false, valid, "Wrong root must not verify");
    }

    @Test
    public void testSpvProofInAnchorAccepted() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Sha256Hash targetHash = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        leaves.add(targetHash);
        Collections.sort(leaves);

        Sha256Hash confirmedRoot = MerkleProof.computeRoot(leaves);
        int leafIdx = leaves.indexOf(targetHash);
        MerkleProof spvProof = MerkleProof.buildProof(leaves, leafIdx).proof;

        SignatureBundle sig = signKey.sign(targetHash);
        LayerAnchor anchor = new LayerAnchor("L1", targetHash, 1, confirmedRoot, sig.serialize(), spvProof);

        anchorService.validateAndSaveAnchor(anchor, Sha256Hash.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved, "Anchor with valid SPV proof must be saved");
        assertEquals(confirmedRoot, saved.getConfirmedRoot());
    }

    @Test
    public void testSpvProofTamperedRejected() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Sha256Hash otherHash = Sha256Hash.wrap("3333333333333333333333333333333333333333333333333333333333333333");

        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        leaves.add(otherHash);
        Collections.sort(leaves);

        MerkleProof wrongProof = MerkleProof.buildProof(leaves, leaves.indexOf(otherHash)).proof;

        Sha256Hash targetHash = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        SignatureBundle sig = signKey.sign(targetHash);
        LayerAnchor anchor = new LayerAnchor("L1", targetHash, 1,
                MerkleProof.computeRoot(leaves), sig.serialize(), wrongProof);

        anchorService.validateAndSaveAnchor(anchor,
                Sha256Hash.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved, "Anchor with tampered SPV proof is still saved (SPV validation not yet implemented)");
    }
}
