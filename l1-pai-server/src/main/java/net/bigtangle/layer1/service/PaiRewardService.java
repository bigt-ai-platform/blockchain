package net.bigtangle.layer1.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class PaiRewardService {

    private static final Logger log = LoggerFactory.getLogger(PaiRewardService.class);

    public List<RewardSummary> getRewardHistory(String contractTokenId, BlockStoreInterface store)
            throws BlockStoreException {
        List<Contractresult> results = getContractResultChain(contractTokenId, store);
        List<RewardSummary> history = new ArrayList<>();
        for (Contractresult cr : results) {
            RewardSummary s = new RewardSummary();
            s.epochId = cr.getChainlength();
            s.txHash = cr.getBlockHash() != null ? cr.getBlockHash().toString() : "";
            s.timestamp = cr.getTime();
            s.contractTokenId = contractTokenId;
            history.add(s);
        }
        return history;
    }

    public Contractresult getLatestContractResult(String contractTokenId, BlockStoreInterface store)
            throws BlockStoreException {
        return store.getMaxConfirmedContractresult(contractTokenId);
    }

    private List<Contractresult> getContractResultChain(String contractTokenId, BlockStoreInterface store)
            throws BlockStoreException {
        Contractresult max = store.getMaxConfirmedContractresult(contractTokenId);
        if (max == null) return Collections.emptyList();
        List<Contractresult> results = new ArrayList<>();
        results.add(max);
        Sha256Hash prev = max.getPrevblockhash();
        int maxDepth = 1000;
        while (prev != null && !Sha256Hash.ZERO_HASH.equals(prev) && maxDepth-- > 0) {
            Contractresult cr = store.getContractresult(prev);
            if (cr == null) break;
            results.add(cr);
            prev = cr.getPrevblockhash();
        }
        return results;
    }

    public static class RewardSummary {
        public long epochId;
        public String txHash;
        public long timestamp;
        public String contractTokenId;
        public BigInteger totalDistributed = BigInteger.ZERO;
    }
}
