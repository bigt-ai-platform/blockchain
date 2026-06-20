package net.bigtangle.layer0.service;

import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Coin;
import net.bigtangle.core.Token;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.UTXOProviderException;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.GetOutputsResponse;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

/**
 * Layer-0 service for payment transactions and output/balance queries.
 * Wraps {@link OutputService} and adds payment-specific convenience methods.
 */
@Service
public class PaymentTransactionService {

    @Autowired
    private OutputService outputService;

    // -- Balance queries --

    public AbstractResponse getAccountBalance(Set<byte[]> pubKeyHashes, BlockStoreInterface store)
            throws BlockStoreException, UTXOProviderException {
        return outputService.getAccountBalanceInfo(pubKeyHashes, store);
    }

    public AbstractResponse getAccountBalanceFromAccount(Set<byte[]> pubKeyHashes, BlockStoreInterface store)
            throws BlockStoreException, UTXOProviderException {
        return outputService.getAccountBalanceInfoFromAccount(pubKeyHashes, store);
    }

    // -- Spend candidates (building payment transactions) --

    public LinkedList<TransactionOutput> getSpendCandidates(Set<byte[]> pubKeyHashes, BlockStoreInterface store)
            throws BlockStoreException, UTXOProviderException {
        return outputService.calculateAllSpendCandidatesFromUTXOProvider(pubKeyHashes, store);
    }

    // -- UTXO / output queries --

    public List<UTXO> getOpenOutputs(String tokenid, BlockStoreInterface store) throws UTXOProviderException {
        return outputService.getOpenAllOutputs(tokenid, store);
    }

    public GetOutputsResponse getOpenOutputsResponse(String tokenid, BlockStoreInterface store)
            throws BlockStoreException, UTXOProviderException {
        return outputService.getOpenAllOutputsResponse(tokenid, store);
    }

    public GetOutputsResponse getAccountOutputs(Set<byte[]> pubKeyHashes, BlockStoreInterface store)
            throws BlockStoreException {
        return outputService.getAccountOutputs(pubKeyHashes, store);
    }

    public List<UTXO> getOpenOutputsForAddress(String address, BlockStoreInterface store)
            throws UTXOProviderException, IOException {
        return outputService.getOpenTransactionOutputs(address, store);
    }

    // -- Token name resolution --

    public Map<String, Token> resolveTokenNames(List<UTXO> utxos, BlockStoreInterface store) throws BlockStoreException {
        return outputService.getTokename(utxos, store);
    }

    public Map<String, Token> resolveTokenNamesByCoin(List<Coin> coins, BlockStoreInterface store)
            throws BlockStoreException {
        return outputService.getTokenameByCoin(coins, store);
    }

    // -- Validation --

    public boolean isValidAddress(String address) {
        return outputService.checkValidAddress(address);
    }
}
