package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

public class StakeIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StakeIT.class);

    @Autowired
    private StakeService stakeService;

    private BlockStoreInterface store;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(storeService.getStore());
        mcmcService.calcNewBlockPrototype(storeService.getStore());
        store = storeService.getStore();
    }

    @Test
    public void testStakeLifecycle() throws Exception {
        PQKey validatorKey = PQKey.createNew();
        BigInteger stakeAmount = StakeService.MIN_STAKE;

        // Fund the validator
        Block fb = payBigTo(validatorKey, stakeAmount.add(BigInteger.valueOf(100000)), null);
        if (fb != null) {
            blockGraph.updateChain(false);
            mcmcService.update(store);
            mcmcService.calcNewBlockPrototype(store);
        }
        log.info("Funded validator");

        // Create a deposit block
        Block proto = cacheBlockPrototypeService.getBlockPrototype(store);
        Block depositBlock = Block.createBlock(networkParameters,
                store.get(proto.getPrevBlockHash()),
                store.get(proto.getPrevBranchBlockHash()));
        depositBlock.setBlockType(BlockType.BLOCKTYPE_BEACON);
        store.put(depositBlock);
        log.info("Created deposit block");

        // Register stake deposit
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), stakeAmount, validatorKey.getPubKeyHash()));
        log.info("Saved stake deposit");

        // Verify deposit exists
        StakeRecord saved = store.getStakeDeposit(validatorKey.getPubKey());
        assertNotNull(saved, "Stake deposit should be saved");
        assertEquals(stakeAmount, saved.getAmount());
        log.info("Stake deposit verified: amount={}", saved.getAmount());

        // Activate validator
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);
        log.info("Activated validator at epoch 0");

        // Verify active
        List<StakeRecord> active = store.getActiveStakeDeposits();
        boolean found = false;
        for (StakeRecord sr : active) {
            if (java.util.Arrays.equals(sr.getPubkey(), validatorKey.getPubKey())) {
                found = true;
                assertEquals(stakeAmount, sr.getAmount());
                break;
            }
        }
        assertTrue(found, "Validator should be in active stake deposits");
        log.info("Validator active: {} active validators", active.size());

        // Verify effective stake
        long effective = stakeService.getEffectiveStake(validatorKey.getPubKey(), store);
        assertEquals(stakeAmount.longValue(), effective);
        log.info("Effective stake: {}", effective);
    }

    @Test
    public void testMultipleValidators() throws Exception {
        PQKey v1 = PQKey.createNew();
        PQKey v2 = PQKey.createNew();
        BigInteger amount1 = StakeService.MIN_STAKE;
        BigInteger amount2 = StakeService.MIN_STAKE.multiply(BigInteger.valueOf(2));

        // Fund and stake v1
        Block fb1 = payBigTo(v1, amount1.add(BigInteger.valueOf(100000)), null);
        if (fb1 != null) {
            blockGraph.updateChain(false);
            mcmcService.update(store);
            mcmcService.calcNewBlockPrototype(store);
        }
        Block proto1 = cacheBlockPrototypeService.getBlockPrototype(store);
        Block db1 = Block.createBlock(networkParameters,
                store.get(proto1.getPrevBlockHash()),
                store.get(proto1.getPrevBranchBlockHash()));
        db1.setBlockType(BlockType.BLOCKTYPE_BEACON);
        store.put(db1);
        store.saveStakeDeposit(new StakeRecord(v1.getPubKey(), amount1, v1.getPubKeyHash()));
        stakeService.activateValidator(v1.getPubKey(), 0, store);

        // Fund and stake v2
        Block fb2 = payBigTo(v2, amount2.add(BigInteger.valueOf(100000)), null);
        if (fb2 != null) {
            blockGraph.updateChain(false);
            mcmcService.update(store);
            mcmcService.calcNewBlockPrototype(store);
        }
        Block proto2 = cacheBlockPrototypeService.getBlockPrototype(store);
        Block db2 = Block.createBlock(networkParameters,
                store.get(proto2.getPrevBlockHash()),
                store.get(proto2.getPrevBranchBlockHash()));
        db2.setBlockType(BlockType.BLOCKTYPE_BEACON);
        store.put(db2);
        store.saveStakeDeposit(new StakeRecord(v2.getPubKey(), amount2, v2.getPubKeyHash()));
        stakeService.activateValidator(v2.getPubKey(), 0, store);

        // Verify total active stake
        BigInteger total = stakeService.getTotalActiveStake(store);
        assertEquals(amount1.add(amount2), total,
                "Total active stake should be sum of both validators");
        log.info("Total active stake: {}", total);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testActivateAndQueryViaHttp() throws Exception {
        PQKey validatorKey = PQKey.createNew();
        BigInteger stakeAmount = StakeService.MIN_STAKE;

        // Fund via service
        Block fb = payBigTo(validatorKey, stakeAmount.add(BigInteger.valueOf(100000)), null);
        if (fb != null) {
            blockGraph.updateChain(false);
            mcmcService.update(store);
            mcmcService.calcNewBlockPrototype(store);
        }

        // Save stake deposit via service
        Block proto = cacheBlockPrototypeService.getBlockPrototype(store);
        Block depositBlock = Block.createBlock(networkParameters,
                store.get(proto.getPrevBlockHash()),
                store.get(proto.getPrevBranchBlockHash()));
        depositBlock.setBlockType(BlockType.BLOCKTYPE_BEACON);
        store.put(depositBlock);
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), stakeAmount, validatorKey.getPubKeyHash()));
        store.close();

        // Activate via HTTP API
        String pubkeyHex = Utils.HEX.encode(validatorKey.getPubKey());
        HashMap<String, Object> activateReq = new HashMap<>();
        activateReq.put("pubkey", pubkeyHex);
        activateReq.put("epoch", 0L);
        byte[] actResp = OkHttp3Util.postString(contextRoot + ReqCmd.activateValidator.name(),
                Json.jsonmapper().writeValueAsString(activateReq));
        log.info("activateValidator response: {}", new String(actResp));

        // Query validators via HTTP API. getValidators wraps the JSON payload in
        // GetStringResponse.text, so unwrap it when the top-level "validators"
        // key is absent (see ProdSimVerification).
        byte[] queryResp = OkHttp3Util.postString(contextRoot + ReqCmd.getValidators.name(), "{}");
        Map<String, Object> wrapper = Json.jsonmapper().readValue(queryResp, HashMap.class);
        Object validatorsObj = wrapper.get("validators");
        if (validatorsObj == null && wrapper.get("text") instanceof String) {
            Map<String, Object> inner = Json.jsonmapper()
                    .readValue((String) wrapper.get("text"), HashMap.class);
            validatorsObj = inner.get("validators");
        }
        List<Map<String, Object>> validators = (List<Map<String, Object>>) validatorsObj;
        assertNotNull(validators, "Validators list should not be null");

        boolean found = false;
        for (Map<String, Object> v : validators) {
            // getValidators serializes StakeRecord via Jackson, which encodes
            // byte[] (pubkey) as base64 — decode and compare with the hex key.
            Object pk = v.get("pubkey");
            if (pk == null) continue;
            byte[] pkBytes;
            try {
                pkBytes = java.util.Base64.getDecoder().decode((String) pk);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (java.util.Arrays.equals(validatorKey.getPubKey(), pkBytes)) {
                found = true;
                log.info("Found validator via HTTP");
                break;
            }
        }
        assertTrue(found, "Validator should be findable via getValidators HTTP API");
        log.info("Validator activated and confirmed via HTTP. Total: {}", validators.size());
    }

    /**
     * Drives the REAL stakeDeposit path end-to-end: fund a validator with a
     * confirmed BIG UTXO, then call {@link StakeService#processDeposit}. That
     * builds and saves a BLOCKTYPE_STAKE block through
     * {@code BlockSaveService.saveBlock}, whose {@code accumulateBlockFees}
     * batch-reads the spent input via {@code store.getTransactionOutputs}.
     *
     * <p>Regression test: the batch UTXO query SELECT (added in the perf commit)
     * omitted the {@code outputindex} column while the row loop read it, so every
     * processDeposit threw PSQLException and no STAKE block was ever saved —
     * which silently blocked the entire PoS validator bootstrap.
     */
    @Test
    public void testProcessDepositSavesStakeBlock() throws Exception {
        PQKey validatorKey = PQKey.createNew();
        BigInteger stakeAmount = StakeService.MIN_STAKE;

        // Insert a confirmed, spendable BIG output owned by the validator key
        // with a proper P2PKH script (addConfirmedBigUtxo reuses the genesis
        // script, which does not match an arbitrary key and fails verify).
        Block genesis = net.bigtangle.core.UtilGeneseBlock.createGenesis(networkParameters);
        UTXO funded = new UTXO();
        funded.setHash(genesis.getTransactions().get(0).getHash());
        funded.setIndex(2_000_000_000L + System.nanoTime() % 1_000_000L);
        funded.setValue(new Coin(stakeAmount.add(BigInteger.valueOf(100000)),
                net.bigtangle.params.NetworkParameters.BIGTANGLE_TOKENID));
        funded.setCoinbase(true);
        funded.setScript(net.bigtangle.script.ScriptBuilder.createOutputScript(validatorKey));
        funded.setAddress(net.bigtangle.core.Address
                .fromHash160(networkParameters, Utils.sha256hash160(validatorKey.getPubKey())).toBase58());
        funded.setBlockHash(genesis.getHash());
        funded.setTokenid(net.bigtangle.params.NetworkParameters.BIGTANGLE_TOKENID_STRING);
        funded.setConfirmed(true);
        funded.setSpent(false);
        store.addUnspentTransactionOutput(new ArrayList<>(java.util.List.of(funded)));

        // processDeposit requires an OPEN (confirmed, unspent) output for the key.
        String addr = net.bigtangle.core.Address.fromHash160(networkParameters,
                Utils.sha256hash160(validatorKey.getPubKey())).toBase58();
        List<UTXO> open = store.getOpenTransactionOutputs(addr);
        assertTrue(!open.isEmpty(), "funded validator must have an open BIG output");

        // This is the exact path that was broken: processDeposit -> saveBlock ->
        // accumulateBlockFees -> getTransactionOutputs.
        store.close();
        store = storeService.getStore();
        stakeService.processDeposit(open.get(0), validatorKey.getPubKey(), validatorKey, store);

        // A STAKE block must have been persisted with the deposit.
        List<UTXO> spentAfter = store.getOpenTransactionOutputs(addr);
        StakeRecord saved = store.getStakeDeposit(validatorKey.getPubKey());
        log.info("Stake deposit processed; open outputs remaining: {}", spentAfter.size());
        // The deposit UTXO was spent and the STAKE block saved without exception.
        assertTrue(spentAfter.size() <= open.size(),
                "staking must spend the funded output (open " + open.size() + " -> " + spentAfter.size() + ")");
        assertNotNull(saved, "store should report the deposit after processDeposit");
        assertTrue(saved.getAmount().compareTo(stakeAmount) >= 0,
                "staked amount must be at least the minimum stake (was " + saved.getAmount() + ")");
    }

    /**
     * Direct regression test for the batch UTXO query: insert confirmed outputs
     * with distinct indices and verify {@code getTransactionOutputs} returns all
     * of them keyed by index. This is the SQL that threw 'The column name
     * outputindex was not found in this ResultSet' when the SELECT column list
     * was missing outputindex.
     */
    @Test
    public void testGetTransactionOutputsBatchQuery() throws Exception {
        // Use the genesis coinbase as the referencing block/tx so the rows are
        // valid, but give each output a unique index.
        Block genesis = net.bigtangle.core.UtilGeneseBlock.createGenesis(networkParameters);
        byte[] txHash = genesis.getTransactions().get(0).getHash().getBytes();
        byte[] blockHash = genesis.getHash().getBytes();
        byte[] script = genesis.getTransactions().get(0).getOutput(0).getScriptPubKey().getProgram();

        List<UTXO> toInsert = new ArrayList<>();
        List<Long> indices = new ArrayList<>();
        for (long i = 1001; i <= 1004; i++) {
            UTXO u = new UTXO();
            u.setHash(genesis.getTransactions().get(0).getHash());
            u.setIndex(i);
            u.setValue(new Coin(BigInteger.valueOf(i), net.bigtangle.params.NetworkParameters.BIGTANGLE_TOKENID));
            u.setCoinbase(true);
            u.setScript(new net.bigtangle.script.Script(script));
            u.setAddress(genesis.getTransactions().get(0).getOutput(0).getScriptPubKey().getToAddress(networkParameters)
                    .toBase58());
            u.setBlockHash(genesis.getHash());
            u.setTokenid(net.bigtangle.params.NetworkParameters.BIGTANGLE_TOKENID_STRING);
            u.setConfirmed(true);
            u.setSpent(false);
            toInsert.add(u);
            indices.add(i);
        }
        store.addUnspentTransactionOutput(toInsert);

        // The buggy SQL read rs.getLong("outputindex") without SELECTing it;
        // this call must not throw and must return all four outputs.
        java.util.Map<Long, UTXO> got = store.getTransactionOutputs(
                genesis.getHash(), genesis.getTransactions().get(0).getHash(), indices);
        assertEquals(4, got.size(), "batch query must return all requested outputs");
        for (Long idx : indices) {
            assertTrue(got.containsKey(idx), "result must contain output index " + idx);
        }
        log.info("getTransactionOutputs returned {} outputs", got.size());
    }
}
