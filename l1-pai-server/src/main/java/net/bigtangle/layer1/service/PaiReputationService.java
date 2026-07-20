package net.bigtangle.layer1.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.Token;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.layer1.contract.PaiEngine;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class PaiReputationService {

    private static final Logger log = LoggerFactory.getLogger(PaiReputationService.class);

    public Map<String, Long> getLatestReputationScores(Token contract, BlockStoreInterface store)
            throws BlockStoreException {
        List<ContractEventRecord> events = store.getContractEventRecordOpen(contract.getTokenid());
        if (events == null || events.isEmpty()) {
            return Collections.emptyMap();
        }
        return PaiEngine.computeReputationScores(events);
    }
}
