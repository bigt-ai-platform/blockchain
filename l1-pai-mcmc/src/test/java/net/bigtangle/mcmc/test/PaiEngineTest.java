package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.Utils;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.layer1.contract.PaiEngine;
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
        Set<Sha256Hash> referenced = new HashSet<>();

        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());

        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);

        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, referenced);
        assertNotNull(result);
        assertNull(result.getOutputTx());
    }

    @Test
    public void testReputationContractDispatch() throws Exception {
        Token contract = createContractTokenWithClassname(CLASSNAME_REPUTATION);
        PaiEngine engine = new PaiEngine();

        Contractresult prev = Contractresult.firstContractresult();
        Set<Sha256Hash> referenced = new HashSet<>();

        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());

        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);

        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, referenced);
        assertNotNull(result);
    }

    @Test
    public void testRewardContractDispatch() throws Exception {
        Token contract = createContractTokenWithClassname(CLASSNAME_REWARD);
        PaiEngine engine = new PaiEngine();

        Contractresult prev = Contractresult.firstContractresult();
        Set<Sha256Hash> referenced = new HashSet<>();

        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());

        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);

        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, referenced);
        assertNotNull(result);
    }

    @Test
    public void testUnknownContractReturnsNull() throws Exception {
        Token contract = createContractTokenWithClassname("net.bigtangle.server.service.UnknownContract");
        PaiEngine engine = new PaiEngine();

        Contractresult prev = Contractresult.firstContractresult();
        Set<Sha256Hash> referenced = new HashSet<>();

        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());

        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);

        ContractExecutionResult result = engine.executeContract(support, networkParameters, block, store,
                contract, prev, referenced);
        assertNull(result);
    }

    private Token createContractTokenWithClassname(String classname) {
        TokenKeyValues kvs = new TokenKeyValues();
        KeyValue kv = new KeyValue();
        kv.setKey("classname");
        kv.setValue(classname);
        kvs.addKeyvalue(kv);

        Token token = new Token();
        token.setTokenid(Sha256Hash.create(classname.getBytes()).toString());
        token.setTokenKeyValues(kvs);
        return token;
    }
}
