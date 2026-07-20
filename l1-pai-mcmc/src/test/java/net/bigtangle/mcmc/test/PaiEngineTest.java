package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.layer1.contract.PaiEngine;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.service.base.ServiceBaseConnect;

public class PaiEngineTest extends AbstractIntegrationTest {

    private static final String CLASSNAME_STAKING = "net.bigtangle.server.service.AiStakingContract";
    private static final String CLASSNAME_REPUTATION = "net.bigtangle.server.service.AiReputationContract";
    private static final String CLASSNAME_REWARD = "net.bigtangle.server.service.AiRewardContract";

    @Test
    public void testStakingContractDispatch() throws Exception {
        Token contract = createContractTokenWithClassname(CLASSNAME_STAKING);
        PaiEngine engine = new PaiEngine();
        Contractresult prev = Contractresult.firstContractresult();
        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);
        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, new HashSet<>());
        assertNotNull(result);
    }

    @Test
    public void testReputationContractDispatch() throws Exception {
        Token contract = createContractTokenWithClassname(CLASSNAME_REPUTATION);
        PaiEngine engine = new PaiEngine();
        Contractresult prev = Contractresult.firstContractresult();
        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);
        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, new HashSet<>());
        assertNotNull(result);
    }

    @Test
    public void testRewardContractDispatch() throws Exception {
        Token contract = createContractTokenWithClassname(CLASSNAME_REWARD);
        PaiEngine engine = new PaiEngine();
        Contractresult prev = Contractresult.firstContractresult();
        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);
        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, new HashSet<>());
        assertNotNull(result);
    }

    @Test
    public void testUnknownContractReturnsNull() throws Exception {
        Token contract = createContractTokenWithClassname("net.bigtangle.server.service.UnknownContract");
        PaiEngine engine = new PaiEngine();
        Contractresult prev = Contractresult.firstContractresult();
        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);
        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, new HashSet<>());
        assertNull(result);
    }

    @Test
    public void testStakeEventProcessedIntoRemainder() throws Exception {
        Token contract = createContractTokenWithClassname(CLASSNAME_STAKING);
        String providerAddr = "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG";
        Sha256Hash prevResultHash = Sha256Hash.create("prevResult".getBytes());

        ContractEventRecord event = new ContractEventRecord(
                Sha256Hash.create("evt1".getBytes()), prevResultHash, contract.getTokenid(),
                true, false, Sha256Hash.ZERO_HASH,
                Coin.COIN.multiply(10).getValue(), networkParameters.BIGTANGLE_TOKENID_STRING, providerAddr);
        store.insertContractEvent(Collections.singletonList(event));

        Contractresult prev = new Contractresult(prevResultHash, true, false, Sha256Hash.ZERO_HASH, null,
                null, contract.getTokenid(), 0, 0, System.currentTimeMillis());
        PaiEngine engine = new PaiEngine();
        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);
        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, new HashSet<>());
        assertNotNull(result);
        assertEquals(1, result.getRemainderRecords().size());
        assertEquals(event.getBlockHash(), result.getRemainderRecords().iterator().next());
        assertNull(result.getOutputTx());
    }
    @Test
    public void testRewardDispatchNonEmpty() throws Exception {
        Token contract = createContractTokenWithClassname(CLASSNAME_REWARD);
        Contractresult prev = Contractresult.firstContractresult();
        PaiEngine engine = new PaiEngine();
        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);
        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, new HashSet<>());
        assertNotNull(result);
    }

    @Test
    public void testBuildPayoutTx() {
        Map<String, BigInteger> perBeneficiary = new HashMap<>();
        perBeneficiary.put("mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG", Coin.COIN.multiply(100).getValue());
        Transaction tx = PaiEngine.buildPayoutTx(networkParameters, perBeneficiary);
        assertNotNull(tx);
        assertEquals(1, tx.getOutputs().size());
        TransactionOutput output = tx.getOutputs().get(0);
        assertEquals(Coin.COIN.multiply(100), output.getValue());
    }

    @Test
    public void testBuildPayoutTxMultipleBeneficiaries() {
        Map<String, BigInteger> perBeneficiary = new HashMap<>();
        perBeneficiary.put("mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG", Coin.COIN.multiply(50).getValue());
        perBeneficiary.put("mnyfpjZUx2Uk2yXQZ9iUE5nxKRhwVXQEwF", Coin.COIN.multiply(30).getValue());
        Transaction tx = PaiEngine.buildPayoutTx(networkParameters, perBeneficiary);
        assertNotNull(tx);
        assertEquals(2, tx.getOutputs().size());
    }

    @Test
    public void testBuildPayoutTxEmpty() {
        Map<String, BigInteger> perBeneficiary = new HashMap<>();
        Transaction tx = PaiEngine.buildPayoutTx(networkParameters, perBeneficiary);
        assertNotNull(tx);
        assertEquals(0, tx.getOutputs().size());
    }

    @Test
    public void testComputeReputationScores() {
        String addr1 = "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG";
        String addr2 = "mnyfpjZUx2Uk2yXQZ9iUE5nxKRhwVXQEwF";

        Sha256Hash hash1 = Sha256Hash.create("rep1".getBytes());
        Sha256Hash hash2 = Sha256Hash.create("rep2".getBytes());
        Sha256Hash contractId = Sha256Hash.create("contract".getBytes());

        ContractEventRecord r1 = new ContractEventRecord(hash1, Sha256Hash.ZERO_HASH, contractId.toString(),
                true, false, Sha256Hash.ZERO_HASH,
                BigInteger.valueOf(50), NetworkParameters.BIGTANGLE_TOKENID_STRING, addr1);
        ContractEventRecord r2 = new ContractEventRecord(hash2, Sha256Hash.ZERO_HASH, contractId.toString(),
                true, false, Sha256Hash.ZERO_HASH,
                BigInteger.valueOf(30), NetworkParameters.BIGTANGLE_TOKENID_STRING, addr2);

        Map<String, Long> scores = PaiEngine.computeReputationScores(List.of(r1, r2));
        assertEquals(2, scores.size());
        assertTrue(scores.containsKey(addr1));
        assertTrue(scores.containsKey(addr2));
        assertEquals(142L, scores.get(addr1).longValue());
        assertEquals(123L, scores.get(addr2).longValue());
    }

    @Test
    public void testComputeReputationScoresClampsAt1000() {
        String addr = "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG";
        Sha256Hash hash = Sha256Hash.create("bigrep".getBytes());
        Sha256Hash contractId = Sha256Hash.create("contract".getBytes());

        ContractEventRecord r = new ContractEventRecord(hash, Sha256Hash.ZERO_HASH, contractId.toString(),
                true, false, Sha256Hash.ZERO_HASH,
                BigInteger.valueOf(5000), NetworkParameters.BIGTANGLE_TOKENID_STRING, addr);

        Map<String, Long> scores = PaiEngine.computeReputationScores(List.of(r));
        assertTrue(scores.get(addr) <= 1000L);
        assertEquals(950L, scores.get(addr).longValue());
    }

    @Test
    public void testComputeTotalStaked() {
        String addr = "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG";
        Sha256Hash hash1 = Sha256Hash.create("stk1".getBytes());
        Sha256Hash hash2 = Sha256Hash.create("stk2".getBytes());
        Sha256Hash contractId = Sha256Hash.create("contract".getBytes());

        ContractEventRecord r1 = new ContractEventRecord(hash1, Sha256Hash.ZERO_HASH, contractId.toString(),
                true, false, Sha256Hash.ZERO_HASH,
                Coin.COIN.multiply(100).getValue(), NetworkParameters.BIGTANGLE_TOKENID_STRING, addr);
        ContractEventRecord r2 = new ContractEventRecord(hash2, Sha256Hash.ZERO_HASH, contractId.toString(),
                true, false, Sha256Hash.ZERO_HASH,
                Coin.COIN.multiply(50).getValue(), NetworkParameters.BIGTANGLE_TOKENID_STRING, addr);

        BigInteger staked = PaiEngine.computeTotalStaked(List.of(r1, r2), addr);
        assertEquals(Coin.COIN.multiply(150).getValue(), staked);
    }

    @Test
    public void testComputeTotalStakedMultipleProviders() {
        String addr1 = "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG";
        String addr2 = "mnyfpjZUx2Uk2yXQZ9iUE5nxKRhwVXQEwF";
        Sha256Hash hash1 = Sha256Hash.create("a".getBytes());
        Sha256Hash hash2 = Sha256Hash.create("b".getBytes());
        Sha256Hash contractId = Sha256Hash.create("c".getBytes());

        ContractEventRecord r1 = new ContractEventRecord(hash1, Sha256Hash.ZERO_HASH, contractId.toString(),
                true, false, Sha256Hash.ZERO_HASH,
                Coin.COIN.multiply(100).getValue(), NetworkParameters.BIGTANGLE_TOKENID_STRING, addr1);
        ContractEventRecord r2 = new ContractEventRecord(hash2, Sha256Hash.ZERO_HASH, contractId.toString(),
                true, false, Sha256Hash.ZERO_HASH,
                Coin.COIN.multiply(200).getValue(), NetworkParameters.BIGTANGLE_TOKENID_STRING, addr2);

        assertEquals(Coin.COIN.multiply(100).getValue(), PaiEngine.computeTotalStaked(List.of(r1, r2), addr1));
        assertEquals(Coin.COIN.multiply(200).getValue(), PaiEngine.computeTotalStaked(List.of(r1, r2), addr2));
        assertEquals(BigInteger.ZERO, PaiEngine.computeTotalStaked(List.of(r1, r2), "unknown"));
    }

}
