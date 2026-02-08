/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.checkpoint;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Tokensums;
import net.bigtangle.core.TokensumsMap;
import net.bigtangle.core.UTXO;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.UTXOProviderException;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class CheckpointService {
	private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

 

	@Autowired
	protected StoreService storeService; 
 
	// private static final Logger log =
	// LoggerFactory.getLogger(CheckpointService.class);

	private List<UTXO> getOutputs(String tokenid, BlockStoreInterface store)
			throws UTXOProviderException, BlockStoreException {
		// Must be sorted with the key of
		return store.getOpenAllOutputs(tokenid);
	}

 

	private List<OrderRecord> orders(String tokenid, BlockStoreInterface store) throws BlockStoreException {
		return store.getAllOpenOrdersSorted(null, tokenid);

	}

	private List<ContractEventRecord> contracts(String tokenid, BlockStoreInterface store) throws BlockStoreException {
		return store.getContractEventRecordOpen(  tokenid);

	}

	
	public Map<String, BigInteger> tokensumInitial(BlockStoreInterface store) throws BlockStoreException {

		return store.getTokenAmountMap();
	}

	public TokensumsMap checkToken(BlockStoreInterface store) throws BlockStoreException, UTXOProviderException {

		TokensumsMap tokensumset = new TokensumsMap();

		Map<String, BigInteger> tokensumsInitial = tokensumInitial(store);
		Set<String> tokenids = tokensumsInitial.keySet();
		for (String tokenid : tokenids) {
			Tokensums tokensums = new Tokensums();
			tokensums.setTokenid(tokenid);
			tokensums.setUtxos(getOutputs(tokenid, store));
			tokensums.setOrders(orders(tokenid, store));
			tokensums.setInitial(tokensumsInitial.get(tokenid));
			tokensums.setContracts(contracts(tokenid, store));
			tokensums.calculate();
			tokensumset.getTokensumsMap().put(tokenid, tokensums);
		}
		return tokensumset;
	}
}
