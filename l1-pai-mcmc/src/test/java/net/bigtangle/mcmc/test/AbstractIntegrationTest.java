package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.l1.pai.PaiL1MCMCStart;
import net.bigtangle.layer1.contract.PaiEngine;
import net.bigtangle.mcmc.service.MCMCService;
import net.bigtangle.mcmc.service.RewardService;
import net.bigtangle.mcmc.service.TipsService;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.BlockService;
import net.bigtangle.server.service.BlockServiceCreate;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.SyncBlockService;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = PaiL1MCMCStart.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = { "server.net=Test", "service.schedule.initsync=false", "service.schedule.mcmc=false" })
@TestExecutionListeners(value = { DependencyInjectionTestExecutionListener.class,
        DirtiesContextTestExecutionListener.class })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    private static final String CONTEXT_ROOT_TEMPLATE = "http://localhost:%s/";
    protected static final Logger log = LoggerFactory.getLogger(AbstractIntegrationTest.class);
    public String contextRoot;
    public Wallet wallet;
    protected final java.security.Key aesKey = null;

    @Autowired protected BlockStoreService blockGraph;
    @Autowired protected BlockService blockService;
    @Autowired protected BlockServiceCreate blockServiceCreate;
    @Autowired protected MCMCService mcmcService;
    @Autowired protected RewardService rewardService;
    @Autowired protected NetworkParameters networkParameters;
    @Autowired protected StoreService storeService;
    @Autowired protected TipsService tipsService;
    @Autowired protected SyncBlockService syncBlockService;
    @Autowired protected ServerConfiguration serverConfiguration;
    @Autowired protected CacheBlockService cacheBlockService;
    @Autowired protected ScheduleConfiguration scheduleConfiguration;
    @Autowired protected CacheBlockPrototypeService cacheBlockPrototypeService;
    @Autowired protected BlockSaveService blockSaveService;
    @Autowired protected transient javax.sql.DataSource dataSource;

    protected static ObjectMapper objectMapper = new ObjectMapper();
    public BlockStoreInterface store;

    public static String testPub = "02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975";
    public static String testPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";

    @Autowired
    protected void prepareContextRoot(@Value("${local.server.port}") int port) {
        contextRoot = String.format(CONTEXT_ROOT_TEMPLATE, port);
    }

    @BeforeEach
    public void setUp() throws Exception {
        Utils.unsetMockClock();
        scheduleConfiguration.setInitSync(false);
        store = storeService.getStore();
        resetStore();
        wallet = Wallet.fromKeys(networkParameters,
                ECKey.fromPrivate(Utils.HEX.decode(testPriv)), contextRoot);
        serverConfiguration.setServiceReady(true);
    }

    @AfterEach
    public void close() throws Exception {
        store.close();
    }

    public void resetStore() throws BlockStoreException {
        store.resetStore();
        cacheBlockService.evictOutputs();
        cacheBlockService.evictBlock();
        cacheBlockService.evictAccountBalance();
        cacheBlockService.evictMaxConfirmedReward();
        cacheBlockService.evictBlockMCMC();
        cacheBlockService.evictBlockEvaluation();
    }

    protected Block makeRewardBlock(List<Block> addedBlocks) throws Exception {
        Sha256Hash prevRewardHash = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
        Block reward = rewardService.createReward(prevRewardHash, store);
        blockGraph.updateChain(false);
        if (addedBlocks != null) addedBlocks.add(reward);
        return reward;
    }

    protected Block rewardWithBlock(List<Block> addedBlocks, Block b) throws Exception {
        if (b != null) {
            if (addedBlocks != null) addedBlocks.add(b);
            Block block = makeRewardBlock(b);
            if (addedBlocks != null && block != null) addedBlocks.add(block);
            return block;
        } else {
            return makeRewardBlock(addedBlocks);
        }
    }

    protected Block makeRewardBlock(Block predecessor) throws Exception {
        Sha256Hash prevRewardHash = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);
        BlockWrap prev = support.getBlockWrap(predecessor.getHash(), store);
        Block block = rewardService.createReward(prevRewardHash, prev, prev, store);
        return block;
    }

    protected Block makeAndAddBlock(Block predecessor) throws Exception {
        Block block = Block.createBlock(networkParameters, predecessor, predecessor);
        block.solve();
        this.blockGraph.addBlock(block, true, store);
        return block;
    }

    protected Block makeAndConfirmBlock(List<Block> addedBlocks, Block predecessor) throws Exception {
        Block block = Block.createBlock(networkParameters, predecessor, predecessor);
        block.solve();
        this.blockGraph.addBlock(block, true, store);
        addedBlocks.add(block);
        makeRewardBlock(addedBlocks);
        return block;
    }

    protected Block createAndAddNextBlock(Block b1, Block b2) throws Exception {
        Block block = Block.createBlock(networkParameters, b1, b2);
        this.blockGraph.addBlock(block, true, store);
        return block;
    }

    protected List<UTXO> getBalance(boolean withZero, List<ECKey> keys) throws Exception {
        List<UTXO> listUTXO = new ArrayList<>();
        List<String> keyStrHex000 = new ArrayList<>();
        for (ECKey ecKey : keys) {
            keyStrHex000.add(Utils.HEX.encode(ecKey.getPubKeyHash()));
        }
        byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.getBalances.name(),
                Json.jsonmapper().writeValueAsString(keyStrHex000).getBytes());
        GetBalancesResponse getBalancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);
        for (UTXO utxo : getBalancesResponse.getOutputs()) {
            if (withZero || utxo.getValue().getValue().signum() > 0) {
                listUTXO.add(utxo);
            }
        }
        return listUTXO;
    }

    protected List<UTXO> getBalance(boolean withZero, ECKey ecKey) throws Exception {
        List<ECKey> keys = new ArrayList<>();
        keys.add(ecKey);
        return getBalance(withZero, keys);
    }

    protected List<UTXO> getBalance() throws Exception {
        return getBalance(false);
    }

    protected List<UTXO> getBalance(boolean withZero) throws Exception {
        return getBalance(withZero, wallet.walletKeys(null));
    }

    protected UTXO getLargeUTXO(List<UTXO> outputs) {
        UTXO a = outputs.get(0);
        for (UTXO b : outputs) {
            if (b.getValue().isGreaterThan(a.getValue())) a = b;
        }
        return a;
    }

    protected Block payBigTo(ECKey beneficiary, BigInteger amount, List<Block> addedBlocks) throws Exception {
        HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
        giveMoneyResult.put(beneficiary.toAddress(networkParameters).toString(), amount);
        return payList(addedBlocks, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID);
    }

    private Block payList(List<Block> addedBlocks, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid)
            throws Exception {
        Block b = wallet.payMoneyToECKeyList(null, giveMoneyResult, tokenid, "payList");
        if (addedBlocks != null) addedBlocks.add(b);
        if (b != null) {
            Block re = makeRewardBlock(b);
            if (addedBlocks != null) addedBlocks.add(re);
        }
        return b;
    }

    protected ContractExecutionResult executePaiContract(Block block, BlockStoreInterface store,
            String contractid, Contractresult prevHash, Set<Sha256Hash> referencedblocks)
            throws BlockStoreException {
        ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, objectMapper);
        return new PaiEngine().executeContract(support, networkParameters, block, store,
                contractid, prevHash, referencedblocks);
    }
}
