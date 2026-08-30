package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        // The integration store does not run Casper finality; the default
        // finality gate would defer every peg-out. Tests opt out here and
        // exercise the gate explicitly in testPegOutDeferredUntilFinalized.
        bridgeConfiguration.setRequireFinality(false);
        // Reset any per-chain registry left by a previous test.
        anchorConfiguration.setChainPubKeys(new java.util.HashMap<>());
        // Reset any L0-side freeze left by a previous test.
        anchorConfiguration.setDisabledChains(new java.util.HashSet<>());
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

        // The vault is keyed on the SOURCE outpoint, so find it that way —
        // getVaultUTXOsByChainId does not guarantee insertion order.
        return vaultBySource(source.getBlockHash(), source.getIndex());
    }

    /**
     * Generalized peg-in: locks a given (ANY-token) UTXO {@code source} into the
     * vault and returns the newly created vault record. Used to prove the flow
     * invariant holds per token, not just for BIG.
     */
    private VaultRecord pegInUtxo(PQKey userKey, UTXO source, String beneficiary) throws Exception {
        Address vault = Address.fromHash160(networkParameters, Utils.sha256hash160(vaultKey.getPubKey()));
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

        return vaultBySource(source.getBlockHash(), source.getIndex());
    }

    /** The (unspent) vault record keyed on the given source UTXO outpoint. */
    private VaultRecord vaultBySource(Sha256Hash sourceBlockHash, long sourceIndex) throws Exception {
        for (VaultRecord v : store.getVaultUTXOsByChainId(L1_CHAIN_ID, false)) {
            if (v.getUtxoBlockHash().equals(sourceBlockHash) && v.getUtxoIndex() == sourceIndex) {
                return v;
            }
        }
        fail("peg-in must create a vault for source " + sourceBlockHash + ":" + sourceIndex);
        return null;
    }

    /** Sum of the vault records for (chain, token) matching the given spent flags. */
    private BigInteger vaultSum(String tokenIdHex, boolean spent) throws Exception {
        BigInteger sum = BigInteger.ZERO;
        for (VaultRecord v : store.getVaultUTXOsByChainId(L1_CHAIN_ID, spent)) {
            if (tokenIdHex.equals(v.getTokenIdHex())) {
                sum = sum.add(BigInteger.valueOf(v.getAmount()));
            }
        }
        return sum;
    }

    /**
     * ROUND-TRIP FLOW INVARIANT (per token): cumulative L1->L0 (peg-out /
     * released) must NEVER exceed cumulative L0->L1 (peg-in / locked). The test
     * drives several full round trips for BIG and one for a custom token, and
     * asserts at every step that {@code released(token) <= locked(token)} — no
     * token can be withdrawn more than it was deposited.
     */
    @Test
    public void testPerTokenPegOutNeverExceedsPegIn() throws Exception {
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        String bigHex = Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID);

        // Three BIG deposits (L0 -> L1).
        VaultRecord v1 = createRealVault(testKey, recipient, 100000);
        VaultRecord v2 = createRealVault(testKey, recipient, 200000);
        VaultRecord v3 = createRealVault(testKey, recipient, 300000);

        // One custom-token deposit (L0 -> L1): the invariant is per-token.
        List<Block> added = new ArrayList<>();
        makeTestToken(testKey, added);
        UTXO customSource = null;
        for (UTXO u : getBalance(false, List.of(testKey))) {
            if (u.getValue().getValue().signum() > 0
                    && !java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, u.getValue().getTokenid())) {
                customSource = u;
                break;
            }
        }
        assertNotNull(customSource, "testKey must hold the custom token UTXO after makeTestToken");
        VaultRecord cv = pegInUtxo(testKey, customSource, recipient);
        String customHex = Utils.HEX.encode(customSource.getValue().getTokenid());

        // After the deposits: everything locked, nothing released.
        BigInteger bigLocked = BigInteger.valueOf(v1.getAmount() + v2.getAmount() + v3.getAmount());
        assertEquals(bigLocked, vaultSum(bigHex, false).add(vaultSum(bigHex, true)),
                "BIG locked == sum of all BIG vaults");
        assertEquals(BigInteger.ZERO, vaultSum(bigHex, true), "no BIG released yet");
        assertEquals(BigInteger.valueOf(cv.getAmount()),
                vaultSum(customHex, false).add(vaultSum(customHex, true)), "custom token locked");
        assertEquals(BigInteger.ZERO, vaultSum(customHex, true), "no custom token released yet");

        // Withdraw two of the three BIG deposits (L1 -> L0).
        confirmBurnAndPegOut(v1, recipient, 90);
        confirmBurnAndPegOut(v2, recipient, 91);

        BigInteger bigReleased = BigInteger.valueOf(v1.getAmount() + v2.getAmount());
        assertEquals(bigReleased, vaultSum(bigHex, true));
        assertTrue(bigReleased.compareTo(bigLocked) < 0,
                "BIG released must be strictly below BIG locked while a deposit is outstanding");
        assertEquals(BigInteger.ZERO, vaultSum(customHex, true),
                "withdrawing BIG must not touch the custom token's flow");

        // Withdraw the last BIG deposit: released == locked (balanced round trip).
        confirmBurnAndPegOut(v3, recipient, 92);
        assertEquals(bigLocked, vaultSum(bigHex, true),
                "BIG round trip must end exactly balanced: released == locked");
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

    /**
     * Resolves the ACTUAL vault output created by the peg-in CROSSTANGLE block
     * (the output the release must spend). Reading it straight from the store
     * (not the VaultRecord) lets the attack tests assert on the real locked
     * value/token and on the spender once the release happened.
     */
    private UTXO resolveVaultOutput(VaultRecord vault) throws Exception {
        Sha256Hash pegInBlockHash = vault.getPegInBlockHash() != null
                ? vault.getPegInBlockHash() : vault.getUtxoBlockHash();
        Block pegInBlock = store.get(pegInBlockHash);
        assertNotNull(pegInBlock, "peg-in block must exist");
        assertFalse(pegInBlock.getTransactions().isEmpty(), "peg-in block must carry the lock tx");
        Sha256Hash vaultTxHash = pegInBlock.getTransactions().get(0).getHash();
        UTXO utxo = store.getTransactionOutput(pegInBlockHash, vaultTxHash, 0);
        assertNotNull(utxo, "vault output must exist in the store");
        return utxo;
    }

    /**
     * ROUND-TRIP INVARIANT: L0 lock (peg-in) followed by the L1 burn / L0
     * release (peg-out) must be NET-NEUTRAL — the exact amount and token that
     * went into the vault must come out, no more (inflation) and no less
     * (destruction). The wrapped supply on L1 is 1:1 backed by these vault
     * locks, so any divergence here would mint or burn value.
     */
    @Test
    public void testRoundTripConservesValueExactly() throws Exception {
        long amount = 100000;
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        VaultRecord vault = createRealVault(testKey, recipient, amount);
        long locked = vault.getAmount();
        String tokenIdHex = Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID);

        // Leg 1 (L0 -> L1): the vault output holds EXACTLY the recorded amount
        // and token — the L1 wrapped mint is 1:1 on this, so no over-issue.
        UTXO vaultOut = resolveVaultOutput(vault);
        assertEquals(BigInteger.valueOf(locked), vaultOut.getValue().getValue(),
                "peg-in must lock exactly the recorded amount (1:1, no inflation)");
        assertEquals(tokenIdHex, Utils.HEX.encode(vaultOut.getValue().getTokenid()),
                "peg-in must lock the SAME token id (no cross-token substitution)");

        // Leg 2 (L1 -> L0): burn + release. A reward block confirms the release
        // CROSSTANGLE block so the vault output is marked spent on-chain.
        confirmBurnAndPegOut(vault, recipient, 70);
        makeRewardBlock();

        // The vault output is spent exactly once, by the release tx.
        UTXO spent = resolveVaultOutput(vault);
        assertTrue(spent.isSpent(), "peg-out must spend the vault output");
        assertNotNull(spent.getSpenderBlockHash(), "release block must be recorded");
        Transaction releaseTx = store.get(spent.getSpenderBlockHash()).getTransactions().get(0);

        // The release pays the recipient EXACTLY the locked value, same token.
        assertEquals(1, releaseTx.getOutputs().size(),
                "release must pay a single output (all-or-nothing, R5)");
        assertEquals(BigInteger.valueOf(locked), releaseTx.getOutput(0).getValue().getValue(),
                "round trip must return exactly the locked value (nothing created or destroyed)");
        assertEquals(tokenIdHex, Utils.HEX.encode(releaseTx.getOutput(0).getValue().getTokenid()),
                "round trip must return the SAME token id");
    }

    /**
     * ATTACK: the L1 burn names a DIFFERENT token id than the vault actually
     * holds. If L0 honoured it, an attacker could convert the locked BIG
     * collateral into a release denominated in any other token — moving value
     * that was never backed (using more tokens than were locked).
     */
    @Test
    public void testPegOutRejectsCrossTokenBurn() throws Exception {
        long amount = 100000;
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        VaultRecord vault = createRealVault(testKey, recipient, amount);

        // A valid signed + SPV-verified anchor, but the burn's token differs
        // from the vault's actual token.
        String foreignTokenHex = Utils.HEX.encode(PQKey.createNew().getPubKey());
        assertFalse(foreignTokenHex.equals(vault.getTokenIdHex()),
                "test setup must use a genuinely foreign token id");

        long height = 75;
        Sha256Hash head = Sha256Hash.wrap(String.format("%064x", height));
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        java.util.Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        LayerAnchor.AnchorBurn burn = new LayerAnchor.AnchorBurn(
                vault.getUtxoBlockHash().toString() + ":" + vault.getUtxoIndex(), recipient,
                vault.getAmount(), foreignTokenHex);
        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":" + height,
                head, height, root, null, proof, burn);
        anchor.setSignature(anchor.sign(testKey).serialize());

        anchorService.validateAndSaveAnchor(anchor, head, store);
        store.updateAnchorConfirmed(L1_CHAIN_ID, height, true);

        bridgeService.processPegOut(store.getAnchorByChainIdAndHeight(L1_CHAIN_ID, height), store);

        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertEquals(1, unspent.size(),
                "a cross-token burn must NOT release the vault");
        assertFalse(unspent.get(0).isSpent(),
                "the locked collateral must stay locked (no cross-token value migration)");
    }

    /**
     * ATTACK: L1 sends a burn for a FICTIVE token — a token id that was never
     * locked on L0, pointing at a vault that does not exist. This must be a
     * harmless no-op on L0: nothing is released, the real vaults stay locked,
     * and — crucially — L1 is NOT dead: a subsequent legitimate peg-out for the
     * same chain still works.
     */
    @Test
    public void testFictiveTokenBurnIsHarmlessNoOp() throws Exception {
        long amount = 100000;
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        String bigHex = Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID);
        VaultRecord vault = createRealVault(testKey, recipient, amount);

        long height = 95;
        Sha256Hash head = Sha256Hash.wrap(String.format("%064x", height));
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        java.util.Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        // A burn in a token that has no vault on L0, referencing a vault outpoint
        // that does not exist.
        LayerAnchor.AnchorBurn burn = new LayerAnchor.AnchorBurn(
                Sha256Hash.of("fictive".getBytes()).toString() + ":7", recipient,
                vault.getAmount(), Utils.HEX.encode(PQKey.createNew().getPubKey()));
        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":" + height,
                head, height, root, null, proof, burn);
        anchor.setSignature(anchor.sign(testKey).serialize());

        anchorService.validateAndSaveAnchor(anchor, head, store);
        store.updateAnchorConfirmed(L1_CHAIN_ID, height, true);

        bridgeService.processPegOut(store.getAnchorByChainIdAndHeight(L1_CHAIN_ID, height), store);

        // Harmless no-op: the real vault is untouched, no BIG was released.
        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertEquals(1, unspent.size(), "fictive-token burn must not release anything");
        assertFalse(unspent.get(0).isSpent(), "the real vault must stay locked");
        assertEquals(BigInteger.ZERO, vaultSum(bigHex, true), "no BIG released by the fictive burn");

        // L1 is NOT dead: a legitimate peg-out right after still succeeds.
        confirmBurnAndPegOut(vault, recipient, 96);
        assertEquals(BigInteger.valueOf(vault.getAmount()), vaultSum(bigHex, true),
                "L1 keeps functioning after a rejected fictive-token burn");
    }

    /**
     * SOLUTION FOR AN UNTRUSTED / BROKEN L1 (L0-side freeze): when an L1 chain
     * misbehaves (attack or software error), L0 can put it in
     * {@code anchor.disabledChains}. While frozen:
     * <ul>
     * <li>L0 rejects ALL new anchors from that chain (they cannot even be
     *     recorded), so no fresh burn can be settled;</li>
     * <li>L0 ignores even a burn that was confirmed BEFORE the freeze (the retry
     *     loop keeps re-attempting it), so no collateral leaves for that chain.</li>
     * </ul>
     * The vault stays locked on L0, which lets the collateral later be recovered
     * to the ORIGINAL depositors (L0 keeps the peg-in records) before the L1
     * chain is rebuilt. This is the "halt" half of "freeze then recreate L1".
     */
    @Test
    public void testFrozenChainFreezesPegOut() throws Exception {
        long amount = 100000;
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        VaultRecord vault = createRealVault(testKey, recipient, amount);

        // Freeze the chain on L0.
        anchorConfiguration.setDisabledChains(java.util.Set.of(L1_CHAIN_ID));
        assertTrue(anchorConfiguration.isChainDisabled(L1_CHAIN_ID));

        // (1) New anchors from a frozen chain are rejected outright.
        LayerAnchor minimal = new LayerAnchor(L1_CHAIN_ID, Sha256Hash.ZERO_HASH, 0, null, null, null);
        assertThrows(Exception.class, () -> anchorService.validateAndSaveAnchor(minimal, Sha256Hash.ZERO_HASH, store),
                "anchors from a frozen chain must be rejected by L0");

        // (2) Even a burn confirmed BEFORE the freeze is never honored.
        AnchorRecord preFreezeBurn = new AnchorRecord();
        preFreezeBurn.setChainId(L1_CHAIN_ID);
        preFreezeBurn.setL1RewardHeadHash(Sha256Hash.ZERO_HASH);
        preFreezeBurn.setL1Height(1);
        preFreezeBurn.setBlockHash(Sha256Hash.ZERO_HASH);
        preFreezeBurn.setConfirmed(true);
        preFreezeBurn.setBurnJson(new LayerAnchor.AnchorBurn(
                vault.getUtxoBlockHash().toString() + ":" + vault.getUtxoIndex(), recipient,
                vault.getAmount(), Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID)).toJson());
        store.saveAnchor(preFreezeBurn);

        bridgeService.processPegOut(preFreezeBurn, store);

        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertEquals(1, unspent.size(), "a frozen chain must never release a vault");
        assertFalse(unspent.get(0).isSpent(),
                "the collateral of a frozen chain must stay locked on L0 (ready for recovery)");
    }

    /**
     * ATTACK: two independent CONFIRMED anchors (different L1 heights) both
     * carry a valid burn for the SAME vault — a replay of the peg-out leg. The
     * first release spends the collateral; the second must be a no-op, or the
     * attacker would withdraw the same collateral twice (minting tokens from
     * nothing).
     */
    @Test
    public void testPegOutReplayCannotDoubleRelease() throws Exception {
        long amount = 100000;
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        VaultRecord vault = createRealVault(testKey, recipient, amount);

        // First release, then a reward block so the release CROSSTANGLE block
        // confirms and the vault output is marked spent on-chain.
        confirmBurnAndPegOut(vault, recipient, 80);
        makeRewardBlock();
        // Replay: a SECOND confirmed anchor for the SAME vault must be a no-op.
        confirmBurnAndPegOut(vault, recipient, 81);

        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertTrue(unspent.isEmpty(), "no unspent vault may remain after the first release");
        List<VaultRecord> spent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, true);
        assertEquals(1, spent.size(), "a vault must be released EXACTLY once");
        assertTrue(spent.get(0).isSpent());

        // The recipient received the release exactly once.
        UTXO spentOut = resolveVaultOutput(vault);
        assertTrue(spentOut.isSpent());
        assertNotNull(spentOut.getSpenderBlockHash());
        Transaction releaseTx = store.get(spentOut.getSpenderBlockHash()).getTransactions().get(0);
        assertEquals(1, releaseTx.getOutputs().size());
        assertEquals(BigInteger.valueOf(vault.getAmount()), releaseTx.getOutput(0).getValue().getValue(),
                "the recipient must receive the locked value exactly once, never twice");
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

    /**
     * FINALITY GATE: with {@code bridge.requireFinality=true} (production
     * default) a CONFIRMED anchor whose L0 block is not yet Casper-finalized must
     * NOT release the vault — confirmation is reversible, finality is not. The
     * release is deferred (the retry service re-attempts it) rather than
     * performed early and later rolled back.
     */
    @Test
    public void testPegOutDeferredUntilFinalized() throws Exception {
        bridgeConfiguration.setRequireFinality(true);

        long amount = 100000;
        String recipient = Address.fromHash160(networkParameters, testKey.getPubKeyHash()).toBase58();
        VaultRecord vault = createRealVault(testKey, recipient, amount);

        // A confirmed anchor with a valid burn, but its block hash is synthetic
        // (not a real block with a chainlength at/below a finalized checkpoint).
        Sha256Hash head = Sha256Hash.wrap("4444444444444444444444444444444444444444444444444444444444444444");
        List<Sha256Hash> leaves = new ArrayList<>();
        leaves.add(head);
        leaves.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000000"));
        java.util.Collections.sort(leaves);
        Sha256Hash root = MerkleProof.computeRoot(leaves);
        MerkleProof proof = MerkleProof.buildProofFor(leaves, head);

        LayerAnchor.AnchorBurn burn = new LayerAnchor.AnchorBurn(
                vault.getUtxoBlockHash().toString() + ":" + vault.getUtxoIndex(), recipient,
                vault.getAmount(), Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID));
        LayerAnchor anchor = new LayerAnchor(L1_CHAIN_ID, L1_CHAIN_ID + ":55",
                head, 55, root, null, proof, burn);
        anchor.setSignature(anchor.sign(testKey).serialize());

        anchorService.validateAndSaveAnchor(anchor, head, store);
        store.updateAnchorConfirmed(L1_CHAIN_ID, 55, true);

        AnchorRecord confirmed = store.getAnchorByChainIdAndHeight(L1_CHAIN_ID, 55);
        assertNotNull(confirmed);
        assertTrue(confirmed.isConfirmed());

        bridgeService.processPegOut(confirmed, store);

        // The vault must remain locked: confirmation without finality is not
        // enough to move collateral.
        List<VaultRecord> unspent = store.getVaultUTXOsByChainId(L1_CHAIN_ID, false);
        assertEquals(1, unspent.size(),
                "a confirmed-but-not-finalized anchor must NOT release the vault");
        assertFalse(unspent.get(0).isSpent(),
                "the vault must stay locked until the anchor's L0 block is finalized");
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
