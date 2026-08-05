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
import net.bigtangle.params.ReqCmd;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.MempoolService;
import net.bigtangle.utils.OkHttp3Util;

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

    /** Builds a signature- and SPV-valid anchor over the given head/root. */
    private LayerAnchor validAnchor(PQKey signKey, Sha256Hash head, long height, Sha256Hash root, MerkleProof proof) {
        LayerAnchor anchor = new LayerAnchor("L1", "L1:" + height, head, height, root, null, proof, null);
        anchor.setSignature(anchor.sign(signKey).serialize());
        return anchor;
    }

    @Test
    public void testValidateAndSaveAnchor() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        LayerAnchor anchor = validAnchor(signKey, head, 1, root, proof);
        anchorService.validateAndSaveAnchor(anchor, UtilGeneseBlock.createGenesis(networkParameters).getHash(), store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved, "Anchor should be saved and retrievable");
        assertEquals(root, saved.getConfirmedRoot());
    }

    @Test
    public void testProcessReceivedAnchor() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);
        LayerAnchor anchor = validAnchor(signKey, head, 1, root, proof);

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toByteArray());
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

        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);
        LayerAnchor anchor = validAnchor(signKey, head, 1, root, proof);

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toByteArray());
        crosstangleBlock.addTransaction(tx);

        anchorService.processReceivedAnchor(crosstangleBlock, store);
        anchorService.confirmAnchor(crosstangleBlock, true, store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved);
        assertEquals(true, saved.isConfirmed(),
                "After processReceivedAnchor + confirmAnchor, anchor must be confirmed");
    }

    @Test
    public void testAnchorOverBatchBlockHttpPreservesTypeAndRecords() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);
        LayerAnchor anchor = validAnchor(signKey, head, 1, root, proof);

        // The type mappings that previously dropped the CROSSTANGLE type on the
        // wire path (F4): without them an anchor is queued/batched as TRANSFER
        // and L0 never records it.
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toByteArray());
        assertEquals(BlockType.BLOCKTYPE_CROSSTANGLE, MempoolService.getTransactionType(tx),
                "MempoolService.getTransactionType must map LayerAnchor -> CROSSTANGLE");

        // Full wire path: /batchBlock (HTTP) -> mempool typed queue -> batch
        // drain -> fail-closed save (the L0AnchorHandler now runs) -> bridge
        // records the anchor.
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Block crosstangleBlock = Block.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction anchorTx = new Transaction(networkParameters);
        anchorTx.setDataClassName("LayerAnchor");
        anchorTx.setData(anchor.toByteArray());
        crosstangleBlock.addTransaction(anchorTx);

        OkHttp3Util.post(contextRoot + ReqCmd.batchBlock.name(), crosstangleBlock.bitcoinSerialize());
        int batched = blockSaveService.batchBlocksFromMempool();
        assertTrue(batched >= 1, "the anchor tx must be batched via the typed mempool queue");

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved, "the anchor must be recorded on L0 after the wire path");
    }

    @Test
    public void testInvalidAnchorRejectedOnBatchReceivePath() throws Exception {
        PQKey correctKey = PQKey.createNew();
        configureAnchorWithKey(correctKey);
        PQKey wrongKey = PQKey.createNew();

        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);
        // Valid structure, but signed by the WRONG key.
        LayerAnchor anchor = validAnchor(wrongKey, head, 1, root, proof);

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Block crosstangleBlock = Block.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction anchorTx = new Transaction(networkParameters);
        anchorTx.setDataClassName("LayerAnchor");
        anchorTx.setData(anchor.toByteArray());
        crosstangleBlock.addTransaction(anchorTx);

        OkHttp3Util.post(contextRoot + ReqCmd.batchBlock.name(), crosstangleBlock.bitcoinSerialize());
        assertThrows(Exception.class, () -> blockSaveService.batchBlocksFromMempool(),
                "an anchor signed by the wrong key must be rejected on the batch receive path (fail-closed)");
        assertNull(store.getAnchorByChainIdAndHeight("L1", 1),
                "an invalid anchor must never be recorded");
    }

    @Test
    public void testGetAnchorByBlockHash() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);
        LayerAnchor anchor = validAnchor(signKey, head, 1, root, proof);

        anchorService.validateAndSaveAnchor(anchor, UtilGeneseBlock.createGenesis(networkParameters).getHash(), store);

        AnchorRecord byHash = store.getAnchorByBlockHash(UtilGeneseBlock.createGenesis(networkParameters).getHash());
        assertNotNull(byHash, "getAnchorByBlockHash should find the anchor");
        assertEquals("L1", byHash.getChainId());

        AnchorRecord notFound = store.getAnchorByBlockHash(Sha256Hash.ZERO_HASH);
        assertNull(notFound, "getAnchorByBlockHash should return null for non-existent block hash");
    }

    @Test
    public void testInvalidSignatureRejected() throws Exception {
        PQKey correctKey = PQKey.createNew();
        configureAnchorWithKey(correctKey);

        PQKey wrongKey = PQKey.createNew();
        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);
        // Valid structure, but signed by the WRONG key.
        LayerAnchor anchor = validAnchor(wrongKey, head, 1, root, proof);

        assertThrows(BlockStoreException.class,
                () -> anchorService.validateAndSaveAnchor(anchor, head, store));
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

        LayerAnchor anchor = validAnchor(signKey, targetHash, 1, confirmedRoot, spvProof);

        anchorService.validateAndSaveAnchor(anchor, Sha256Hash.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved, "Anchor with valid SPV proof must be saved");
        assertEquals(confirmedRoot, saved.getConfirmedRoot());
    }

    @Test
    public void testSpvProofTamperedRejected() throws Exception {
        PQKey signKey = PQKey.createNew();
        configureAnchorWithKey(signKey);

        // The anchored head is genuinely in a tree with this root.
        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        Sha256Hash other = Sha256Hash.wrap("3333333333333333333333333333333333333333333333333333333333333333");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        leaves.add(head);
        Collections.sort(leaves);
        Sha256Hash confirmedRoot = MerkleProof.computeRoot(leaves);

        // A proof built from an unrelated leaf set does not bind head to root.
        List<Sha256Hash> wrongLeaves = new ArrayList<>();
        wrongLeaves.add(other);
        wrongLeaves.add(Sha256Hash.wrap("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
        Collections.sort(wrongLeaves);
        MerkleProof wrongProof = MerkleProof.buildProofFor(wrongLeaves, other);

        // Signature is valid (covers the tampered proof), but the proof fails.
        LayerAnchor anchor = new LayerAnchor("L1", "L1:1", head, 1, confirmedRoot, null, wrongProof, null);
        anchor.setSignature(anchor.sign(signKey).serialize());

        assertThrows(BlockStoreException.class,
                () -> anchorService.validateAndSaveAnchor(anchor,
                        Sha256Hash.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), store),
                "Anchor with tampered SPV proof must be rejected");
    }
}
