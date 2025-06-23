/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.subtangle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.response.MultiSignByRequest;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.server.data.SubtangleStatus;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

@Disabled("Temporarily disabled due  ")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
 
public class SubtangleIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private NetworkParameters networkParameters;

    private static final Logger logger = LoggerFactory.getLogger(SubtangleIntegrationTests.class);

    public void createTokenSubtangleId(ECKey ecKey) throws Exception {
        byte[] pubKey = ecKey.getPubKey();
        TokenInfo tokenInfo = new TokenInfo();
        Token tokens = Token.buildSubtangleTokenInfo(false, null, "Test", "Test", "", "");
        tokenInfo.setToken(tokens);
        tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokens.getTokenid(), "", ecKey.getPublicKeyAsHex()));

        Coin basecoin = Coin.valueOf(0L, pubKey);
        HashMap<String, String> requestParam = new HashMap<String, String>();
        byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        Block block = networkParameters.getDefaultSerializer().makeBlock(data);
        block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
        block.addCoinbaseTransaction(ecKey.getPubKey(), basecoin, tokenInfo,null);

        Transaction transaction = block.getTransactions().get(0);
        Sha256Hash sighash = transaction.getHash();
        ECKey.ECDSASignature party1Signature = ecKey.sign(sighash);
        byte[] buf1 = party1Signature.encodeToDER();

        List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
        MultiSignBy multiSignBy0 = new MultiSignBy();
        multiSignBy0.setTokenid(Utils.HEX.encode(pubKey));
        multiSignBy0.setTokenindex(0);
        multiSignBy0.setAddress(ecKey.toAddress(networkParameters).toBase58());
        multiSignBy0.setPublickey(Utils.HEX.encode(ecKey.getPubKey()));
        multiSignBy0.setSignature(Utils.HEX.encode(buf1));
        multiSignBies.add(multiSignBy0);
        MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
        transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

        block.solve();
        OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
    }

    public void giveMoneySubtangleId(ECKey outKey, long amount, Address toAddressInSubtangle) throws Exception {
        ECKey genesiskey =  ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
                Utils.HEX.decode(testPub));

        UTXO findOutput = null;
        for (UTXO output : getBalance(false, genesiskey)) {
            if (Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, output.getValue().getTokenid())) {
                findOutput = output;
            }
        }
        assertTrue(findOutput != null);

        TransactionOutput spendableOutput = new FreeStandingTransactionOutput(networkParameters, findOutput);
        Transaction transaction = new Transaction(networkParameters);
        Coin coinbase = Coin.valueOf(amount, NetworkParameters.BIGTANGLE_TOKENID);
        Address address = outKey.toAddress(this.networkParameters);
        transaction.addOutput(coinbase, address);
        transaction.setToAddressInSubtangle(toAddressInSubtangle.getHash160());

        TransactionInput input = transaction.addInput(findOutput.getBlockHash(), spendableOutput);
        Sha256Hash sighash = transaction.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL,
                false);

        TransactionSignature tsrecsig = new TransactionSignature(genesiskey.sign(sighash), Transaction.SigHash.ALL,
                false);
        Script inputScript = ScriptBuilder.createInputScript(tsrecsig);
        input.setScriptSig(inputScript);

        HashMap<String, String> requestParam = new HashMap<String, String>();
        byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip, Json.jsonmapper().writeValueAsString(requestParam));
        Block rollingBlock = networkParameters.getDefaultSerializer().makeBlock(data);
        rollingBlock.addTransaction(transaction);
        rollingBlock.solve();

        OkHttp3Util.post(contextRoot + ReqCmd.saveBlock.name(), rollingBlock.bitcoinSerialize());
    }

    public Coin getBalanceCoin(ECKey ecKey, byte[] tokenid) throws Exception {
        Coin coinbase = Coin.valueOf(0, tokenid);
        for (UTXO output : getBalance(false, ecKey)) {
            if (Arrays.equals(coinbase.getTokenid(), output.getValue().getTokenid())) {
                coinbase = coinbase.add(output.getValue());
            }
        }
        return coinbase;
    }

    @Autowired
    private SubtangleConfiguration subtangleConfiguration;

     
   // @Test
    public void testGiveMoney() throws Exception {
        logger.info("subtangle configuration active : " + subtangleConfiguration.isActive());
        // assertTrue(subtangleConfiguration.isActive());

        logger.info("subtangle configuration hashKey : " + subtangleConfiguration.getPubKeyHex0());
        ECKey subtangleKey =  ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(subtangleConfiguration.getPriKeyHex0()),
                Utils.HEX.decode(subtangleConfiguration.getPubKeyHex0()));
        // ECKey subtangleKey =  ECKey.fromPrivateAndPrecalculatedPublic();

        // System.out.println(Utils.HEX.encode(subtangleKey.getPubKey()));
        // System.out.println(Utils.HEX.encode(subtangleKey.getPrivKeyBytes()));

        this.createTokenSubtangleId(subtangleKey);

        ECKey outKey =  new ECKey  ();
        long amount = 1000;
        this.giveMoneySubtangleId(subtangleKey, amount, outKey.toAddress(this.networkParameters));

        Coin coinbase = getBalanceCoin(subtangleKey, NetworkParameters.BIGTANGLE_TOKENID);
        logger.info("get balance coin : " + coinbase);

        assertTrue(amount == coinbase.getValue().longValue());
    }
 

    @Test
    public void testUpdateSubtangle() throws Exception {
        String pubkey = "";

        HashMap<String, String> requestParam = new HashMap<String, String>();
        requestParam.put("pubkey", pubkey);
        requestParam.put("userdataPubkey", pubkey);
        requestParam.put("status", SubtangleStatus.OK);
        OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.updateSubtangle, Json.jsonmapper().writeValueAsString(requestParam));
    }
}
