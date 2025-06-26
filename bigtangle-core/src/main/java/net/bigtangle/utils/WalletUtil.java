package net.bigtangle.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.bigtangle.apps.data.SignedData;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.ECIESCoder;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.OrderdataResponse;

public class WalletUtil {
    public static void orderMap(OrderdataResponse orderdataResponse, List<MarketOrderItem> orderData,
            NetworkParameters params, String buy, String sell) {
        for (OrderRecord orderRecord : orderdataResponse.getAllOrdersSorted()) {
            MarketOrderItem marketOrderItem = MarketOrderItem.build(orderRecord, orderdataResponse.getTokennames(),
                    params, buy, sell);
            orderData.add(marketOrderItem);

        }
    }

    public static List<MarketOrderItem> resetOrderList(List<MarketOrderItem> orderList) {
        List<MarketOrderItem> list = new ArrayList<MarketOrderItem>();

        Map<String, Map<String, List<MarketOrderItem>>> orderMap = new HashMap<String, Map<String, List<MarketOrderItem>>>();

        for (MarketOrderItem map : orderList) {
            String tokenname = map.getTokenName();
            String type = map.getType();
            if (!orderMap.containsKey(tokenname)) {
                orderMap.put(tokenname, new HashMap<String, List<MarketOrderItem>>());
            }
            if (!orderMap.get(tokenname).containsKey(type)) {
                orderMap.get(tokenname).put(type, new ArrayList<MarketOrderItem>());
            }

            orderMap.get(tokenname).get(type).add(map);
        }
        for (String tokenname : orderMap.keySet()) {
            Map<String, List<MarketOrderItem>> subMap = orderMap.get(tokenname);
            List<MarketOrderItem> buys = subMap.get("buy");
            if (buys != null) {
                Collections.sort(buys, new Comparator<MarketOrderItem>() {
                    @Override
                    public int compare(MarketOrderItem order1, MarketOrderItem order2) {

                        return order2.getPrice().compareTo(order1.getPrice());
                    }
                });
            }

            List<MarketOrderItem> sells = subMap.get("sell");
            if (sells != null) {
                Collections.sort(sells, new Comparator<MarketOrderItem>() {
                    @Override
                    public int compare(MarketOrderItem order1, MarketOrderItem order2) {
                        return order1.getPrice().compareTo(order2.getPrice());
                    }
                });
            }

            if ("BIG".equals(tokenname)) {
                if (buys != null && !buys.isEmpty()) {
                    list.addAll(0, buys);
                    if (sells != null && !sells.isEmpty()) {
                        list.addAll(buys.size(), sells);
                    }
                } else {
                    if (sells != null && !sells.isEmpty()) {
                        list.addAll(0, sells);
                    }
                }

            } else {
                if (buys != null && !buys.isEmpty()) {
                    list.addAll(buys);
                    if (sells != null && !sells.isEmpty()) {
                        list.addAll(sells);
                    }
                } else {
                    if (sells != null && !sells.isEmpty()) {
                        list.addAll(sells);
                    }
                }
            }
        }
        return list;
    }

    /*
     * return all decrypted SignedData list of the given keys and token type
     */
    public static List<SignedDataWithToken> signedTokenList(List<ECKey> userKeys, TokenType tokenType, String serverurl)
            throws Exception {
        List<SignedDataWithToken> signedTokenList = new ArrayList<SignedDataWithToken>();
        List<String> keys = new ArrayList<String>();
        for (ECKey k : userKeys) {
            keys.add(Utils.HEX.encode(k.getPubKeyHash()));
        }
        byte[] response = OkHttp3Util.post(serverurl + ReqCmd.getBalances.name(),
                Json.jsonmapper().writeValueAsString(keys).getBytes());

        GetBalancesResponse balancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);

        for (UTXO utxo : balancesResponse.getOutputs()) {
            Token token = balancesResponse.getTokennames().get(utxo.getTokenId());
            if (tokenType.ordinal() == token.getTokentype()) {
                signedTokenListAdd(utxo, userKeys, token, signedTokenList);
            }
        }
        return signedTokenList;
    }

    private static void signedTokenListAdd(UTXO utxo, List<ECKey> userkeys, Token token,
            List<SignedDataWithToken> signedTokenList) throws Exception {
        if (token == null || token.getTokenKeyValues() == null) {
            return;
        }
        for (KeyValue kvtemp : token.getTokenKeyValues().getKeyvalues()) {
            ECKey signerKey = getSignedKey(userkeys, kvtemp.getKey());
            if (signerKey != null) {

                    byte[] decryptedPayload = ECIESCoder.decrypt(signerKey.getPrivKey(),
                            Utils.HEX.decode(kvtemp.getValue()));
                    signedTokenList.add(new SignedDataWithToken(new SignedData().parse(decryptedPayload), token));
                    // sdata.verify();
                    break;

            }
        }
    }

    private static ECKey getSignedKey(List<ECKey> userkeys, String pubKey) {
        for (ECKey userkey : userkeys) {
            if (userkey.getPublicKeyAsHex().equals(pubKey)) {
                return userkey;
            }
        }
        return null;
    }

}
