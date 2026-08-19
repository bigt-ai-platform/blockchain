package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
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
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.data.VaultRecord;
import net.bigtangle.utils.Json;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

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
    /** Dedicated vault key (seed-derived so it round-trips bridge.vaultPriKeyHex). */
    private PQKey vaultKey;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        testKey = PQKey.createNew();
        String pubKeyHex = Utils.HEX.encode(testKey.getPublicKeyBytes());

        byte[] vaultSeed = new byte[32];
        new java.security.SecureRandom().nextBytes(vaultSeed);
        vaultKey = PQKey.fromMLDSA(vaultSeed);

        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(pubKeyHex);
        anchorConfiguration.setL0Url("http://localhost:" + port + "/");
        bridgeConfiguration.setActive(true);
        bridgeConfiguration.setVaultPubKeyHex(Utils.HEX.encode(vaultKey.getPublicKeyBytes()));
        bridgeConfiguration.setVaultPriKeyHex(Utils.HEX.encode(vaultSeed));
        // Reset any M-of-N vault config left by a previous test method.
        bridgeConfiguration.setVaultPubKeyHexList(new ArrayList<>());
        bridgeConfiguration.setVaultPriKeyHexList(new ArrayList<>());
        bridgeConfiguration.setVaultM(1);
        // Reset any per-chain registry left by a previous test.
        anchorConfiguration.setChainPubKeys(new java.util.HashMap<>());
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

    /**
     * Builds a REAL vault: funds {@code userKey}, constructs a signed peg-in
     * transaction (spending the user's UTXO, paying the vault, declaring the L1
     * beneficiary + chain id in PegInInfo) and runs it through
     * {@link BridgeService#processPegIn}. Returns the created (unspent) vault.
     */
    private VaultRecord createRealVault(PQKey userKey, String beneficiary, long amount) throws Exception {
        Address vault = Address.fromHash160(networkParameters, Utils.sha256hash160(vaultKey.getPubKey()));
        return createRealVault(userKey, beneficiary, amount, vault);
    }

    /**
     * Like {@link #createRealVault(PQKey, String, long)} but pays an explicit
     * vault address (used for the M-of-N P2SH vault tests).
     */
    private VaultRecord createRealVault(PQKey userKey, String beneficiary, long amount, Address vault)
            throws Exception {
        List<Block> added = new ArrayList<>();
        payBigTo(userKey, BigInteger.valueOf(amount + 100000), added);

        UTXO source = null;
        for (UTXO u : getBalance(false, List.of(userKey))) {
            if (u.getValue().getValue().signum() > 0
                    && java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, u.getValue().getTokenid())) {
                source = u;
                break;
            }
        }
        assertNotNull(source, "user must hold a spendable BIG UTXO after funding");

        Transaction tx = new Transaction(networkParameters);
        tx.setVersion(net.bigtangle.crypto.pq.PQConstants.TX_PQ_VERSION);
        tx.setToAddressInSubtangle(Address.fromBase58(networkParameters, beneficiary).getHash160());
        tx.setDataClassName("PegInInfo");
        tx.setData(Json.jsonmapper().writeValueAsBytes(java.util.Map.of("chainId", L1_CHAIN_ID)));
        FreeStandingTransactionOutput co = new FreeStandingTransactionOutput(networkParameters, source);
        tx.addInput(source.getBlockHash(), co);
        tx.getInputs().get(0).getOutpoint().connectedOutput = co;
        tx.addOutput(source.getValue(), vault);
        Sha256Hash sighash = tx.hashForSignature(0, source.getScript().getProgram(),
                Transaction.SigHash.ALL, false);
        tx.getInputs().get(0).setScriptSig(
                net.bigtangle.script.ScriptBuilder.createInputScriptForPQ(userKey.sign(sighash), userKey));

        bridgeService.processPegIn(tx, store);

        List<VaultRecord> vaults = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertEquals(1, vaults.size(), "peg-in must create exactly one vault");
        return vaults.get(0);
    }

    @Test
    public void testPegOutReleasedWithBurn() throws Exception {
        long amount = 100000;
        String tokenIdHex = Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID);
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();

        // A REAL vault, created by a signed peg-in (not hand-inserted), so the
        // release spends an actual registered vault output and passes the
        // CROSSTANGLE consensus validation (scriptSig + conservation).
        VaultRecord vault = createRealVault(testKey, recipient, amount);

        // Build a signature- and SPV-valid anchor with an embedded burn for this vault.
        Sha256Hash head = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        java.util.Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        LayerAnchor.AnchorBurn burn = new LayerAnchor.AnchorBurn(
                vault.getUtxoBlockHash().toString() + ":" + vault.getUtxoIndex(), recipient,
                vault.getAmount(), tokenIdHex);
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
    public void testPegOutRejectsPartialBurn() throws Exception {
        // R5: a burn of LESS than the full vault amount must NOT release —
        // the remainder would be stranded (the vault record is marked spent and
        // the change UTXO would have no unspent VaultRecord).
        long amount = 100000;
        String tokenIdHex = Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID);
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        VaultRecord vault = createRealVault(testKey, recipient, amount);

        Sha256Hash head = Sha256Hash.wrap("9999999999999999999999999999999999999999999999999999999999999999");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        java.util.Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        // Burn only HALF the vault.
        LayerAnchor.AnchorBurn burn = new LayerAnchor.AnchorBurn(
                vault.getUtxoBlockHash().toString() + ":" + vault.getUtxoIndex(), recipient,
                vault.getAmount() / 2, tokenIdHex);
        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":7",
                head, 7, root, null, proof, burn);
        anchor.setSignature(anchor.sign(testKey).serialize());

        anchorService.validateAndSaveAnchor(anchor, head, store);
        store.updateAnchorConfirmed(L1_CHAIN_ID, 7, true);

        bridgeService.processPegOut(store.getAnchorByChainIdAndHeight(L1_CHAIN_ID, 7), store);

        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertFalse(unspent.isEmpty(), "partial burn must not release the vault");
        assertFalse(unspent.get(0).isSpent(), "partial burn must not mark the vault spent (R5)");
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

    @Test
    public void testPerChainAnchorKeyRegistry() throws Exception {
        // Configure a per-chain registry for L1_CHAIN_ID with a DIFFERENT key.
        PQKey registered = PQKey.createNew();
        anchorConfiguration.setChainPubKeys(java.util.Map.of(L1_CHAIN_ID,
                java.util.List.of(Utils.HEX.encode(registered.getPublicKeyBytes()))));

        Sha256Hash head = Sha256Hash.wrap("3333333333333333333333333333333333333333333333333333333333333333");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        java.util.Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        // Signed by testKey, which is NOT in the chain's registry -> rejected,
        // even though the global anchor.pubKeyHex fallback would accept it.
        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":50",
                head, 50, root, null, proof, null);
        anchor.setSignature(anchor.sign(testKey).serialize());
        assertThrows(Exception.class, () -> anchorService.validateAnchor(anchor),
                "anchor signed by a key outside the chain's registry must be rejected");

        // Signed by the registered key -> accepted.
        LayerAnchor ok = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":50",
                head, 50, root, null, proof, null);
        ok.setSignature(ok.sign(registered).serialize());
        anchorService.validateAnchor(ok);
    }

    /**
     * Builds a confirmed anchor (with a burn for the given vault) at the next
     * free height and runs {@link BridgeService#processPegOut} on it.
     */
    private AnchorRecord confirmBurnAndPegOut(VaultRecord vault, String recipient, long height) throws Exception {
        String tokenIdHex = Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID);
        Sha256Hash head = Sha256Hash.wrap(String.format("%064x", height));
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        java.util.Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        LayerAnchor.AnchorBurn burn = new LayerAnchor.AnchorBurn(
                vault.getUtxoBlockHash().toString() + ":" + vault.getUtxoIndex(), recipient,
                vault.getAmount(), tokenIdHex);
        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":" + height,
                head, height, root, null, proof, burn);
        anchor.setSignature(anchor.sign(testKey).serialize());

        anchorService.validateAndSaveAnchor(anchor, head, store);
        store.updateAnchorConfirmed(L1_CHAIN_ID, height, true);

        AnchorRecord confirmed = store.getAnchorByChainIdAndHeight(L1_CHAIN_ID, height);
        assertNotNull(confirmed);
        assertTrue(confirmed.isConfirmed());
        bridgeService.processPegOut(confirmed, store);
        return confirmed;
    }

    @Test
    public void testMofNVaultPegOutReleasesWithMultisig() throws Exception {
        // 2-of-3 vault: the node holds v1 + v2 private keys; v3 is held
        // elsewhere. The peg-in pays the P2SH vault script and the peg-out
        // release must carry two ordered signatures to pass L0 consensus.
        byte[] s1 = new byte[32];
        byte[] s2 = new byte[32];
        byte[] s3 = new byte[32];
        new java.security.SecureRandom().nextBytes(s1);
        new java.security.SecureRandom().nextBytes(s2);
        new java.security.SecureRandom().nextBytes(s3);
        PQKey v1 = PQKey.fromMLDSA(s1);
        PQKey v2 = PQKey.fromMLDSA(s2);
        PQKey v3 = PQKey.fromMLDSA(s3);

        bridgeConfiguration.setVaultPubKeyHexList(java.util.List.of(
                Utils.HEX.encode(v1.getPublicKeyBytes()),
                Utils.HEX.encode(v2.getPublicKeyBytes()),
                Utils.HEX.encode(v3.getPublicKeyBytes())));
        bridgeConfiguration.setVaultM(2);
        bridgeConfiguration.setVaultPriKeyHexList(java.util.List.of(
                Utils.HEX.encode(s1), Utils.HEX.encode(s2)));

        long amount = 100000;
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        Script redeem = ScriptBuilder.createRedeemScript(2, java.util.List.of(v1, v2, v3));
        Script p2sh = ScriptBuilder.createP2SHOutputScript(redeem);
        Address vaultAddr = Address.fromP2SHScript(networkParameters, p2sh);

        VaultRecord vault = createRealVault(testKey, recipient, amount, vaultAddr);

        confirmBurnAndPegOut(vault, recipient, 60);

        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertTrue(unspent.isEmpty(),
                "2-of-3 peg-out must release and spend the vault (release passes L0 consensus)");
        List<VaultRecord> spent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, true);
        assertEquals(1, spent.size());
        assertTrue(spent.get(0).isSpent());
    }

    @Test
    public void testMofNVaultPegOutSkippedWithoutEnoughSignatures() throws Exception {
        // Same 2-of-3 vault, but the node only holds ONE of the required
        // private keys: the release cannot be signed, so it is skipped and the
        // vault stays locked.
        byte[] s1 = new byte[32];
        byte[] s2 = new byte[32];
        byte[] s3 = new byte[32];
        new java.security.SecureRandom().nextBytes(s1);
        new java.security.SecureRandom().nextBytes(s2);
        new java.security.SecureRandom().nextBytes(s3);
        PQKey v1 = PQKey.fromMLDSA(s1);
        PQKey v2 = PQKey.fromMLDSA(s2);
        PQKey v3 = PQKey.fromMLDSA(s3);

        bridgeConfiguration.setVaultPubKeyHexList(java.util.List.of(
                Utils.HEX.encode(v1.getPublicKeyBytes()),
                Utils.HEX.encode(v2.getPublicKeyBytes()),
                Utils.HEX.encode(v3.getPublicKeyBytes())));
        bridgeConfiguration.setVaultM(2);
        bridgeConfiguration.setVaultPriKeyHexList(java.util.List.of(Utils.HEX.encode(s1)));

        long amount = 100000;
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        Script redeem = ScriptBuilder.createRedeemScript(2, java.util.List.of(v1, v2, v3));
        Address vaultAddr = Address.fromP2SHScript(networkParameters,
                ScriptBuilder.createP2SHOutputScript(redeem));

        VaultRecord vault = createRealVault(testKey, recipient, amount, vaultAddr);

        confirmBurnAndPegOut(vault, recipient, 61);

        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertEquals(1, unspent.size(),
                "a release with too few signatures must be skipped, leaving the vault locked");
        assertFalse(unspent.get(0).isSpent());
    }
}
