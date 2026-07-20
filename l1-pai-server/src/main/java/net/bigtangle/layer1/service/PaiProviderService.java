package net.bigtangle.layer1.service;

import java.math.BigInteger;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.Token;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.layer1.contract.PaiEngine;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class PaiProviderService {

    private static final Logger log = LoggerFactory.getLogger(PaiProviderService.class);

    public BigInteger getStakedAmount(String providerDid, Token contract, BlockStoreInterface store)
            throws BlockStoreException {
        List<ContractEventRecord> openEvents = store.getContractEventRecordOpen(contract.getTokenid());
        if (openEvents == null || openEvents.isEmpty()) {
            return BigInteger.ZERO;
        }
        return PaiEngine.computeTotalStaked(openEvents, providerDid);
    }
}
