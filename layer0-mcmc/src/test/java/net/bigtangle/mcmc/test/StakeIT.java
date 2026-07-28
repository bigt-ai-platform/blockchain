package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
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
import net.bigtangle.core.PQKey;
import net.bigtangle.core.StakeRecord;
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

        // Query validators via HTTP API
        byte[] queryResp = OkHttp3Util.postString(contextRoot + ReqCmd.getValidators.name(), "{}");
        Map<String, Object> result = Json.jsonmapper().readValue(queryResp, HashMap.class);
        List<Map<String, Object>> validators = (List<Map<String, Object>>) result.get("validators");
        assertNotNull(validators, "Validators list should not be null");

        boolean found = false;
        for (Map<String, Object> v : validators) {
            if (pubkeyHex.equals(v.get("pubkey"))) {
                found = true;
                log.info("Found validator via HTTP: {}", v);
                break;
            }
        }
        assertTrue(found, "Validator should be findable via getValidators HTTP API");
        log.info("Validator activated and confirmed via HTTP. Total: {}", validators.size());
    }
}
