package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventInfo;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;

@org.junit.jupiter.api.Disabled("PoS conversion")
public class ContractInlineTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ContractInlineTest.class);

    @Test
    public void testContractExecutionInRewardChain() throws Exception {
        List<Block> blocks = new ArrayList<>();
        ECKey tokenKey = new ECKey();

        makeTestTokenWithSpare(tokenKey, blocks);
        String tokenId = tokenKey.getPublicKeyAsHex();

        ECKey contractKey = new ECKey();
        String winnerAmount = "1000";
        String betAmount = "100";

        TokenKeyValues kv = new TokenKeyValues();
        KeyValue k;
        k = new KeyValue(); k.setKey("system"); k.setValue("java"); kv.addKeyvalue(k);
        k = new KeyValue(); k.setKey("classname"); k.setValue("net.bigtangle.server.service.LotteryContract"); kv.addKeyvalue(k);
        k = new KeyValue(); k.setKey("winnerAmount"); k.setValue(winnerAmount); kv.addKeyvalue(k);
        k = new KeyValue(); k.setKey("amount"); k.setValue(betAmount); kv.addKeyvalue(k);
        k = new KeyValue(); k.setKey("token"); k.setValue(tokenId); kv.addKeyvalue(k);

        TokenInfo contractTokenInfo = new TokenInfo();
        Coin contractCoinbase = new Coin(BigInteger.ONE, contractKey.getPubKey());
        Token contractToken = Token.buildSimpleTokenInfo(true, null,
                contractKey.getPublicKeyAsHex(), "testcontract", "", 1, 0, BigInteger.ONE,
                false, 0,
                UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
        contractToken.setTokenKeyValues(kv);
        contractToken.setTokentype(TokenType.contract.ordinal());
        contractTokenInfo.setToken(contractToken);
        contractTokenInfo.getMultiSignAddresses()
                .add(new MultiSignAddress(contractToken.getTokenid(), "", contractKey.getPublicKeyAsHex()));
        Block contractBlock = saveTokenUnitTest(contractTokenInfo, contractCoinbase, contractKey, null, blocks);
        rewardWithBlock(blocks, contractBlock);

        Token contractTokenRecord = store.getTokenID(contractKey.getPublicKeyAsHex()).get(0);
        assertNotNull(contractTokenRecord);
        log.info("Contract token: {}", contractTokenRecord.getTokenid());

        ECKey user = new ECKey();
        payBigTo(user, Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(10)), blocks);
        makeAndConfirmTransaction(tokenKey, user, tokenId, Long.parseLong(betAmount) * 10, blocks);
        mcmcServiceUpdate();

        Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        Transaction tx = new Transaction(networkParameters);
        ContractEventInfo info = new ContractEventInfo(
                contractTokenRecord.getTokenid(), new BigInteger(betAmount), tokenId,
                user.toAddress(networkParameters).toBase58(), null, null, "");
        tx.setData(info.toByteArray());
        tx.setDataClassName("ContractEventInfo");

        Block eventBlock = Block.createBlock(networkParameters, predecessor, predecessor);
        eventBlock.setBlockType(BlockType.BLOCKTYPE_CONTRACT_EVENT);
        eventBlock.addTransaction(tx);
        eventBlock.solve();
        blockGraph.addBlock(eventBlock, true, store);
        mcmcServiceUpdate();
        log.info("Contract event created");

        makeRewardBlock(blocks);
        log.info("Reward created");

        List<Token> allTokens = store.getTokenTypeList(TokenType.contract.ordinal());
        log.info("Contract tokens: {}", allTokens.size());
        assertTrue(allTokens.size() > 0, "Contract token should exist");
    }
}
