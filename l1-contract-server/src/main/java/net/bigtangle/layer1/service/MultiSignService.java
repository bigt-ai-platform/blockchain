package net.bigtangle.layer1.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.MultiSignByRequest;
import net.bigtangle.response.MultiSignResponse;
import net.bigtangle.response.SearchMultiSignResponse;
import net.bigtangle.response.TokenIndexResponse;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

@Service
public class MultiSignService {

    private static final Logger log = LoggerFactory.getLogger(MultiSignService.class);

    @Autowired
    private NetworkParameters networkParameters;

    public AbstractResponse getMultiSignListWithAddress(final String tokenid, String address, BlockStoreInterface store)
            throws BlockStoreException {
        if (Utils.isBlank(tokenid)) {
            List<MultiSign> multiSigns = store.getMultiSignListByAddress(address);
            return MultiSignResponse.createMultiSignResponse(multiSigns);
        }
        List<MultiSign> multiSigns = store.getMultiSignListByTokenidAndAddress(tokenid, address);
        return MultiSignResponse.createMultiSignResponse(multiSigns);
    }

    public AbstractResponse getCountMultiSign(String tokenid, long tokenindex, int sign, BlockStoreInterface store)
            throws BlockStoreException {
        int count = store.countMultiSign(tokenid, tokenindex, sign);
        return MultiSignResponse.createMultiSignResponse(count);
    }

    public AbstractResponse getMultiSignListWithTokenid(String tokenid, Integer tokenindex, List<String> addresses,
            boolean isSign, BlockStoreInterface store) throws Exception {
        HashSet<String> addressSet = new HashSet<>();
        if (addresses != null) {
            addressSet = new HashSet<>(addresses);
        }
        return getMultiSignListWithTokenid(tokenid, tokenindex, addressSet, isSign, store);
    }

    public AbstractResponse getMultiSignListWithTokenid(String tokenid, Integer tokenindex, Set<String> addresses,
            boolean isSign, BlockStoreInterface store) throws Exception {
        List<MultiSign> multiSigns = store.getMultiSignListByTokenid(tokenid, tokenindex, addresses, isSign);
        List<Map<String, Object>> multiSignList = new ArrayList<>();
        for (MultiSign multiSign : multiSigns) {
            HashMap<String, Object> map = new HashMap<>();
            map.put("id", multiSign.getId());
            map.put("tokenid", multiSign.getTokenid());
            map.put("tokenindex", multiSign.getTokenindex());
            map.put("blockhashHex", multiSign.getBlockhashHex());
            map.put("sign", multiSign.getSign());
            map.put("address", multiSign.getAddress());
            Block block = this.networkParameters.getDefaultSerializer().makeBlock(multiSign.getBlockbytes());
            Transaction transaction = block.getTransactions().get(0);
            TokenInfo tokenInfo = new TokenInfo().parse(transaction.getData());
            map.put("signnumber", tokenInfo.getToken().getSignnumber());
            map.put("tokenname", tokenInfo.getToken().getTokenname());

            Coin fromAmount = new Coin(tokenInfo.getToken().getAmount(), multiSign.getTokenid());
            map.put("amount", fromAmount);
            int signcount;
            if (transaction.getDataSignature() == null) {
                signcount = 0;
            } else {
                String jsonStr = new String(transaction.getDataSignature());
                MultiSignByRequest multiSignByRequest = Json.jsonmapper().readValue(jsonStr,
                        MultiSignByRequest.class);
                signcount = multiSignByRequest.getMultiSignBies().size();
            }
            map.put("signcount", signcount);
            multiSignList.add(map);
        }
        return SearchMultiSignResponse.createSearchMultiSignResponse(multiSignList);
    }

    public AbstractResponse getNextTokenSerialIndex(String tokenid, BlockStoreInterface store) throws BlockStoreException {
        Token tokens = store.getCalMaxTokenIndex(tokenid);
        return TokenIndexResponse.createTokenSerialIndexResponse(tokens.getTokenindex() + 1, tokens.getBlockHash());
    }
}
