package net.bigtangle.performance;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.OrderdataResponse;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.MonetaryFormat;
import net.bigtangle.utils.OkHttp3Util;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OrderYuanTest extends AbstractIntegrationTest {

  

    @Test
    public void payTokenTime() throws Exception {
        wallet.importKey(ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv)));
        wallet.importKey(ECKey.fromPrivate(Utils.HEX.decode(testPriv)));

        ECKey testKey = wallet.walletKeys().get(0);
        List<Block> addedBlocks = new ArrayList<>();

        // base token
        ECKey yuan = ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv));

        long tokennumber = 100000000;
        makeTestToken(yuan, BigInteger.valueOf(tokennumber), addedBlocks, 2);
        // Make test token
        makeTestToken(testKey, BigInteger.valueOf(tokennumber), addedBlocks, 2);
        Address address = wallet.walletKeys().get(0).toAddress(networkParameters);
        Coin amount = MonetaryFormat.FIAT.noCode().parse("1", Utils.HEX.decode(yuanTokenPub), 2);
        List<Long> list = new ArrayList<Long>();
        long time1=System.currentTimeMillis();
        for (int i = 0; i < 160; i++) {
            long start = System.currentTimeMillis();
            wallet.pay(null, address.toString(), amount, new MemoInfo(""));
            makeRewardBlock();
            long end = System.currentTimeMillis();

            list.add(end - start);
        }
        long time2=System.currentTimeMillis();
        list.add(time2 - time1);
        for (Long long1 : list) {
            System.out.println(long1);
        }

    }



 

    public void sell() throws Exception {
        List<UTXO> utxos = getBalance(false, wallet.walletKeys());
        Collections.shuffle(utxos);
        long q = Math.abs((new Random()).nextInt() % 10) + 1;
        for (UTXO utxo : utxos) {
            if (!yuanTokenPub.equals(utxo.getTokenId()) && utxo.getValue().getValue().signum() > 0
                    && !utxo.isSpendPending() && utxo.getValue().getValue().compareTo(BigInteger.valueOf(q)) >= 0) {
                try {
                    wallet.sellOrder(null, utxo.getTokenId(),
                            100000000000l * (Math.abs((new Random()).nextInt() % 10) + 1), q, null, null, yuanTokenPub,
                            true);
                } catch (Exception e) {
                    // TODO: handle exception
                }
            }
        }

    }

    public void buy() throws Exception {

        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        byte[] response0 = OkHttp3Util.post(contextRoot + ReqCmd.getOrders.name(),
                Json.jsonmapper().writeValueAsString(requestParam).getBytes());
        OrderdataResponse orderdataResponse = Json.jsonmapper().readValue(response0, OrderdataResponse.class);

        for (OrderRecord orderRecord : orderdataResponse.getAllOrdersSorted()) {
            try {
                buy(orderRecord);
                makeRewardBlock();
            } catch (Exception e) {
                // TODO: handle exception
            }
        }

    }

    public void buy(OrderRecord orderRecord) throws Exception {
        // sell order and make buy
        long price = orderRecord.getPrice();
        wallet.buyOrder(null, orderRecord.getOfferTokenid(), price, orderRecord.getOfferValue(), null,
                null, orderRecord.getOrderBaseToken(), false);

    }
}
