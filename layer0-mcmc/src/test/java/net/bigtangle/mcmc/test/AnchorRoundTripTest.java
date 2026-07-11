package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.bridge.AnchorConfiguration;
import net.bigtangle.bridge.AnchorService;
import net.bigtangle.bridge.LayerAnchor;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.AnchorRecord;

public class AnchorRoundTripTest extends AbstractIntegrationTest {

    private static final String TEST_PUB = "02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975";
    private static final String TEST_PRIV = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";

    @Autowired
    private AnchorService anchorService;

    @Autowired
    private AnchorConfiguration anchorConfiguration;

    @Test
    public void testValidateAndSaveAnchor() throws Exception {
        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(TEST_PUB);
        anchorConfiguration.setPriKeyHex(TEST_PRIV);

        ECKey signKey = ECKey.fromPrivateAndPrecalculatedPublic(
                Utils.HEX.decode(TEST_PRIV), Utils.HEX.decode(TEST_PUB));

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        long l1Height = 1;

        ECKey.ECDSASignature sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, l1Height, null, sig.encodeToDER());

        anchorService.validateAndSaveAnchor(anchor, genesis.getHash(), store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", l1Height);
        assertNotNull(saved, "Anchor should be saved and retrievable");
    }

    @Test
    public void testProcessReceivedAnchor() throws Exception {
        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(TEST_PUB);
        anchorConfiguration.setPriKeyHex(TEST_PRIV);

        ECKey signKey = ECKey.fromPrivateAndPrecalculatedPublic(
                Utils.HEX.decode(TEST_PRIV), Utils.HEX.decode(TEST_PUB));

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        ECKey.ECDSASignature sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.encodeToDER());

        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toJson().getBytes(StandardCharsets.UTF_8));
        crosstangleBlock.addTransaction(tx);
        crosstangleBlock.solve();

        anchorService.processReceivedAnchor(crosstangleBlock, store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved, "Anchor should be saved after processing received CROSSTANGLE block");
    }

    @Test
    public void testConfirmAnchor() throws Exception {
        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(TEST_PUB);
        anchorConfiguration.setPriKeyHex(TEST_PRIV);

        ECKey signKey = ECKey.fromPrivateAndPrecalculatedPublic(
                Utils.HEX.decode(TEST_PRIV), Utils.HEX.decode(TEST_PUB));

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        ECKey.ECDSASignature sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.encodeToDER());

        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toJson().getBytes(StandardCharsets.UTF_8));
        crosstangleBlock.addTransaction(tx);
        crosstangleBlock.solve();

        store.saveAnchor(new AnchorRecord("L1", l1Hash, 1, null,
                Utils.HEX.encode(sig.encodeToDER()), crosstangleBlock.getHash(), false));

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
        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(TEST_PUB);
        anchorConfiguration.setPriKeyHex(TEST_PRIV);

        ECKey signKey = ECKey.fromPrivateAndPrecalculatedPublic(
                Utils.HEX.decode(TEST_PRIV), Utils.HEX.decode(TEST_PUB));

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        ECKey.ECDSASignature sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.encodeToDER());

        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toJson().getBytes(StandardCharsets.UTF_8));
        crosstangleBlock.addTransaction(tx);
        crosstangleBlock.solve();

        store.saveAnchor(new AnchorRecord("L1", l1Hash, 1, null,
                Utils.HEX.encode(sig.encodeToDER()), crosstangleBlock.getHash(), true));

        anchorService.confirmAnchor(crosstangleBlock, false, store);

        AnchorRecord after = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(after);
        assertEquals(false, after.isConfirmed(),
                "Anchor must be marked unconfirmed after confirmAnchor(false)");
    }

    @Test
    public void testConfirmAnchorViaHandler() throws Exception {
        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(TEST_PUB);
        anchorConfiguration.setPriKeyHex(TEST_PRIV);

        ECKey signKey = ECKey.fromPrivateAndPrecalculatedPublic(
                Utils.HEX.decode(TEST_PRIV), Utils.HEX.decode(TEST_PUB));

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        ECKey.ECDSASignature sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.encodeToDER());

        Block crosstangleBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        crosstangleBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toJson().getBytes(StandardCharsets.UTF_8));
        crosstangleBlock.addTransaction(tx);
        crosstangleBlock.solve();

        anchorService.processReceivedAnchor(crosstangleBlock, store);
        anchorService.confirmAnchor(crosstangleBlock, true, store);

        AnchorRecord saved = store.getAnchorByChainIdAndHeight("L1", 1);
        assertNotNull(saved);
        assertEquals(true, saved.isConfirmed(),
                "After processReceivedAnchor + confirmAnchor, anchor must be confirmed");
    }

    @Test
    public void testGetAnchorByBlockHash() throws Exception {
        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(TEST_PUB);
        anchorConfiguration.setPriKeyHex(TEST_PRIV);

        ECKey signKey = ECKey.fromPrivateAndPrecalculatedPublic(
                Utils.HEX.decode(TEST_PRIV), Utils.HEX.decode(TEST_PUB));

        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash l1Hash = genesis.getHash();
        ECKey.ECDSASignature sig = signKey.sign(l1Hash);
        LayerAnchor anchor = new LayerAnchor("L1", l1Hash, 1, null, sig.encodeToDER());

        anchorService.validateAndSaveAnchor(anchor, genesis.getHash(), store);

        AnchorRecord byHash = store.getAnchorByBlockHash(genesis.getHash());
        assertNotNull(byHash, "getAnchorByBlockHash should find the anchor");
        assertEquals("L1", byHash.getChainId());

        AnchorRecord notFound = store.getAnchorByBlockHash(Sha256Hash.ZERO_HASH);
        assertNull(notFound, "getAnchorByBlockHash should return null for non-existent block hash");
    }

    @Test
    public void testInvalidSignatureRejected() {
        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(TEST_PUB);

        ECKey wrongKey = new ECKey();
        Sha256Hash hash = Sha256Hash.ZERO_HASH;
        ECKey.ECDSASignature sig = wrongKey.sign(hash);

        LayerAnchor anchor = new LayerAnchor("L1", hash, 1, null, sig.encodeToDER());

        assertThrows(BlockStoreException.class,
                () -> anchorService.validateAndSaveAnchor(anchor, hash, store));
    }
}
