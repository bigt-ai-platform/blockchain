package net.bigtangle.layer1.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class TokensService extends net.bigtangle.layer0.service.TokensService {

    public AbstractResponse getContractTokensList(BlockStoreInterface store) throws BlockStoreException {
        List<Token> list = new ArrayList<>(store.getTokenTypeList(TokenType.contract.ordinal()));
        return GetTokensResponse.create(list);
    }
}
