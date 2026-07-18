package net.bigtangle.layer1.service;

import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Token;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class PaiReputationService {

    private static final Logger log = LoggerFactory.getLogger(PaiReputationService.class);

    public Map<String, Float> getLatestReputationScores(Token contract, BlockStoreInterface store)
            throws BlockStoreException {
        // TODO: parse ContractExecutionResult chain for reputation events
        return Collections.emptyMap();
    }
}
