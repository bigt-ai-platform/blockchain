/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Contact;
import net.bigtangle.core.ContactInfo;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.response.AbstractResponse;
import net.bigtangle.core.response.GetTokensResponse;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.store.FullBlockStore;

@Service
public class TokensService {

    @Autowired
    ServerConfiguration serverConfiguration;
    @Autowired
    UserDataService userDataService;
    private static final Logger logger = LoggerFactory.getLogger(TokensService.class);

    public AbstractResponse getTokenById(String tokenid, FullBlockStore store) throws BlockStoreException {
        List<Token> tokens = store.getTokenID(tokenid);
        return GetTokensResponse.create(tokens);
    }

    public AbstractResponse getToken(String blockhashString, FullBlockStore store) throws BlockStoreException {
        List<Token> tokens = new ArrayList<>();
        tokens.add(store.getTokenByBlockHash(Sha256Hash.wrap(blockhashString)));
        return GetTokensResponse.create(tokens);
    }

    public AbstractResponse getWebTokensList(FullBlockStore store) throws BlockStoreException {
        List<Token> list = new ArrayList<>(store.getTokenTypeList(TokenType.web.ordinal()));
        return GetTokensResponse.create(list);
    }
    public AbstractResponse getContractTokensList(FullBlockStore store) throws BlockStoreException {
        List<Token> list = new ArrayList<>(store.getTokenTypeList(TokenType.contract.ordinal()));
        return GetTokensResponse.create(list);
    }

    public GetTokensResponse searchTokens(String name, FullBlockStore store) throws BlockStoreException {
        List<Token> list = new ArrayList<>(store.getTokensList(name));
        Map<String, BigInteger> map = store.getTokenAmountMap();
        return GetTokensResponse.create(list, map);
    }

    public GetTokensResponse searchExchangeTokens(String name, FullBlockStore store)
            throws BlockStoreException {
        List<Token> list = new ArrayList<>();
        if (name != null && !name.trim().isEmpty()) {
            list.addAll(store.getTokensList(name));
        } else {
            addExchangeTokensInUserdata(list, store);
        }
        //Map<String, BigInteger> map = store.getTokenAmountMap();
        return GetTokensResponse.create(list, null);
    }

    public void addExchangeTokensInUserdata(List<Token> list, FullBlockStore store) throws BlockStoreException {
        for (String pubKey : serverConfiguration.getExchangelist()) {
            try {
                byte[] buf = userDataService.getUserData(DataClassName.CONTACTINFO.name(), pubKey, store);
                ContactInfo contactInfo1;
                contactInfo1 = new ContactInfo().parse(buf);
                Set<String> tokenids = new HashSet<>();
                tokenids.add(NetworkParameters.BIGTANGLE_TOKENID_STRING);
                for (Contact contact : contactInfo1.getContactList()) {
                    tokenids.add(contact.getAddress());
                }
                list.addAll(store.getTokenID(tokenids));
                //make sure that BIG is default first
                list.sort(Comparator.comparingInt(s -> s.getTokenid().length()));
                
            } catch (IOException e) {
                logger.info("addExchangeTokensInUserdata", e);
            }
        }
    }

}
