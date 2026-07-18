package net.bigtangle.layer1.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Token;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class PaiProviderService {

    private static final Logger log = LoggerFactory.getLogger(PaiProviderService.class);

    public long getStakedAmount(String providerDid, Token contract, BlockStoreInterface store)
            throws BlockStoreException {
        // TODO: walk contract result chain to aggregate stakes
        return 0;
    }
}
