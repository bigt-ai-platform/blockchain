/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.layer0.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class TokensService {

    public AbstractResponse getTokenById(String tokenid, BlockStoreInterface store) throws BlockStoreException {
        List<Token> tokens = store.getTokenID(tokenid);
        return GetTokensResponse.create(tokens);
    }

    public AbstractResponse getToken(String blockhashString, BlockStoreInterface store) throws BlockStoreException {
        List<Token> tokens = new ArrayList<>();
        tokens.add(store.getTokenByBlockHash(Sha256Hash.wrap(blockhashString)));
        return GetTokensResponse.create(tokens);
    }

    public AbstractResponse getWebTokensList(BlockStoreInterface store) throws BlockStoreException {
        List<Token> list = new ArrayList<>(store.getTokenTypeList(TokenType.web.ordinal()));
        return GetTokensResponse.create(list);
    }
    public GetTokensResponse searchTokens(String name, BlockStoreInterface store) throws BlockStoreException {
        List<Token> list = new ArrayList<>(store.getTokensList(name));
        Map<String, BigInteger> map = store.getTokenAmountMap();
        return GetTokensResponse.create(list, map);
    }

    public GetTokensResponse searchExchangeTokens(String name, BlockStoreInterface store)
            throws BlockStoreException {
        List<Token> list = new ArrayList<>();
        if (name != null && !name.trim().isEmpty()) {
            list.addAll(store.getTokensList(name));
        }
        //Map<String, BigInteger> map = store.getTokenAmountMap();
        return GetTokensResponse.create(list, null);
    }

    /**
     * Search confirmed tokens by name OR token id (case-insensitive substring),
     * capped at 500 results. Used by the wallet's order/balance token selectors.
     */
    public GetTokensResponse searchTokensByNameOrId(String keyword, BlockStoreInterface store)
            throws BlockStoreException {
        return GetTokensResponse.create(store.getTokensByNameOrId(keyword), null);
    }


}
