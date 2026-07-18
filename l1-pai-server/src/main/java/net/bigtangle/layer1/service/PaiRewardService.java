package net.bigtangle.layer1.service;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class PaiRewardService {

    private static final Logger log = LoggerFactory.getLogger(PaiRewardService.class);

    public List<RewardSummary> getRewardHistory(String contractTokenId, BlockStoreInterface store)
            throws BlockStoreException {
        // TODO: walk Contractresult chain, parse ContractExecutionResult, extract payout txs
        return Collections.emptyList();
    }

    public static class RewardSummary {
        public long epochId;
        public String txHash;
        public long timestamp;
        public BigInteger totalDistributed = BigInteger.ZERO;
    }
}
