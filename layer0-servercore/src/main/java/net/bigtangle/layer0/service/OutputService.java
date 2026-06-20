/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.layer0.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;

import net.bigtangle.core.Address;
import net.bigtangle.core.Coin;
import net.bigtangle.core.Token;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.AddressFormatException;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.UTXOProviderException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.GetOutputsResponse;
import net.bigtangle.response.OutputsDetailsResponse;
import net.bigtangle.core.FilterToken;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

@Service
public class OutputService {

	@Autowired
	private NetworkParameters networkParameters;
	@Autowired
	protected CacheBlockService cacheBlockService;

	public AbstractResponse getAccountBalanceInfo(Set<byte[]> pubKeyHashs, BlockStoreInterface store)
			throws BlockStoreException {
		List<UTXO> outputs = new ArrayList<>();
		List<TransactionOutput> transactionOutputs = this.calculateAllSpendCandidatesFromUTXOProvider(pubKeyHashs,
				 store);
		Map<String, Coin> values = new HashMap<>();

		for (TransactionOutput transactionOutput : transactionOutputs) {
			FreeStandingTransactionOutput freeStandingTransactionOutput = (FreeStandingTransactionOutput) transactionOutput;
			UTXO output = freeStandingTransactionOutput.getUTXO();
			outputs.add(output);
			Coin v = output.getValue();
			if (values.containsKey(v.getTokenHex())) {
				Coin nv = values.get(v.getTokenHex()).add(v);
				values.put(output.getValue().getTokenHex(), nv);
			} else {
				values.put(v.getTokenHex(), v);
			}
		}
		List<Coin> tokens = new ArrayList<>();
		for (Map.Entry<String, Coin> entry : values.entrySet()) {
			tokens.add(entry.getValue());
		}

		return GetBalancesResponse.create(tokens, filterToken(outputs), getTokename(outputs, store));
	}

	public AbstractResponse getAccountBalanceInfoFromAccount(Set<byte[]> pubKeyHashs,
															 BlockStoreInterface store) throws BlockStoreException {

		List<Coin> tokens = new ArrayList<>();

		for (byte[] key : pubKeyHashs) {
			Address address =   Address.fromHash160(networkParameters, key);
			List<Coin> accountBalance = cacheBlockService.getAccountBalance(address.toString(), store);
			if (accountBalance != null)
				tokens.addAll(accountBalance);
		}
		return GetBalancesResponse.create(tokens, null, getTokenameByCoin(tokens, store));
	}

	public List<UTXO> filterToken(List<UTXO> outputs) {
		List<UTXO> re = new ArrayList<>();
		for (UTXO u : outputs) {
			if (!FilterToken.filter(u.getTokenId())) {
				re.add(u);
			}
		}
		return re;
	}

	public LinkedList<TransactionOutput> calculateAllSpendCandidatesFromUTXOProvider(Set<byte[]> pubKeyHashs,
		 BlockStoreInterface store) {
		LinkedList<TransactionOutput> candidates = Lists.newLinkedList();
		try {

			for (UTXO output : getStoredOutputsFromUTXOProvider(pubKeyHashs, store)) {
				if (output.isSpent() || !output.isConfirmed())
					continue;
				candidates.add(new FreeStandingTransactionOutput(networkParameters, output));

			}
		} catch (Exception e) {
			throw new RuntimeException("UTXO provider error", e);
		}
		return candidates;
	}

	private List<UTXO> getStoredOutputsFromUTXOProvider(Set<byte[]> pubKeyHashs, BlockStoreInterface store)
			throws UTXOProviderException, IOException {
		List<UTXO> list = new ArrayList<>();
		for (byte[] key : pubKeyHashs) {
			Address address =   Address.fromHash160(networkParameters, key);
			list.addAll(getOpenTransactionOutputs(address.toString(), store));
		}

		return list;
	}

	public List<UTXO> getOpenTransactionOutputs(String address, BlockStoreInterface store)
			throws UTXOProviderException, IOException {
		List<UTXO> re = new ArrayList<>();
		for (byte[] d : cacheBlockService.getOpenTransactionOutputs(address, store)) {
			re.add(Json.jsonmapper().readValue(d, UTXO.class));
		}
		return re;
	}

	public List<UTXO> getOpenAllOutputs(String tokenid, BlockStoreInterface store) throws UTXOProviderException {
		return store.getOpenAllOutputs(tokenid);

	}

	public GetOutputsResponse getOpenAllOutputsResponse(String tokenid, BlockStoreInterface store)
			throws BlockStoreException, UTXOProviderException {
		List<UTXO> outputs = getOpenAllOutputs(tokenid, store);
		return GetOutputsResponse.create(outputs, getTokename(outputs, store));
	}

	public GetOutputsResponse getAccountOutputs(Set<byte[]> pubKeyHashs, BlockStoreInterface store)
			throws BlockStoreException {
		List<UTXO> outputs = new ArrayList<>();
		List<TransactionOutput> transactionOutputs = this.calculateAllSpendCandidatesFromUTXOProvider(pubKeyHashs,
				store);
		for (TransactionOutput transactionOutput : transactionOutputs) {
			FreeStandingTransactionOutput freeStandingTransactionOutput = (FreeStandingTransactionOutput) transactionOutput;
			outputs.add(freeStandingTransactionOutput.getUTXO());
		}
		return GetOutputsResponse.create(outputs, getTokename(outputs, store));
	}

	public GetOutputsResponse getOutputsHistory(String fromaddress, String toaddress, Long starttime, Long endtime,
			BlockStoreInterface store) throws Exception {
		List<UTXO> outputs = new ArrayList<>();
		// no check valid adress
		if ((fromaddress != null && !fromaddress.isEmpty() && checkValidAddress(fromaddress))
				|| (toaddress != null && !toaddress.isEmpty() && checkValidAddress(toaddress))) {
			outputs = store.getOutputsHistory(fromaddress, toaddress, starttime, endtime);
		}
		return GetOutputsResponse.create(filterToken(outputs), getTokename(outputs, store));
	}

	public AbstractResponse getOutputsWithHexStr(String hexStr, BlockStoreInterface store) throws BlockStoreException {
		UTXO output = getStoredOutputsWithHexStr(hexStr, store);
		return OutputsDetailsResponse.create(output);
	}

	private UTXO getStoredOutputsWithHexStr(String hexStr, BlockStoreInterface store) throws BlockStoreException {
		String[] strs = hexStr.split(":");
		byte[] hash = Utils.HEX.decode(strs[0]);
		long outputindex = Long.parseLong(strs[1]);
        return store.getOutputsWithHexStr(hash, outputindex);
	}

	public Map<String, Token> getTokename(List<UTXO> outxos, BlockStoreInterface store) throws BlockStoreException {
		Set<String> tokenids = new HashSet<>();
		for (UTXO d : outxos) {
			tokenids.add(d.getTokenId());

		}
		Map<String, Token> re = new HashMap<>();
		if (!tokenids.isEmpty()) {
			List<Token> tokens = store.getTokensList(tokenids);
			for (Token t : tokens) {
				re.put(t.getTokenid(), t);
			}
		}
		return re;
	}

	public Map<String, Token> getTokenameByCoin(List<Coin> coins, BlockStoreInterface store) throws BlockStoreException {
		Set<String> tokenids = new HashSet<>();
		for (Coin d : coins) {
			tokenids.add(d.getTokenHex());

		}
		Map<String, Token> re = new HashMap<>();
		if (!tokenids.isEmpty()) {
			List<Token> tokens = store.getTokensList(tokenids);
			for (Token t : tokens) {
				re.put(t.getTokenid(), t);
			}
		}
		return re;
	}

	public boolean checkValidAddress(String address) {
		try {
			if (address != null) {
				address = address.trim();
				Address.fromBase58(networkParameters, address);
				return true;
			} else {
				return false;
			}

		} catch (AddressFormatException e1) {

			return false;
		}
	}
}
