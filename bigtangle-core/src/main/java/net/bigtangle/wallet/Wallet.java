/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bigtangle.wallet;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.params.KeyParameter;

import com.google.common.math.LongMath;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventCancelInfo;
import net.bigtangle.core.ContractEventInfo;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.OrderCancelInfo;
import net.bigtangle.core.OrderOpenInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Side;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UserSettingDataInfo;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.DeterministicKey;
import net.bigtangle.crypto.ECIESCoder;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.exception.NoDataException;
import net.bigtangle.exception.NoTokenException;
import net.bigtangle.exception.VerificationException.InvalidTransactionDataException;
import net.bigtangle.exception.VerificationException.OrderImpossibleException;
import net.bigtangle.exception.VerificationException.OrderWithRemainderException;
import net.bigtangle.ordermatch.MatchLastdayResult;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.pool.server.ServerPool;
import net.bigtangle.response.GetDomainTokenResponse;
import net.bigtangle.response.GetOutputsResponse;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.response.MultiSignByRequest;
import net.bigtangle.response.MultiSignResponse;
import net.bigtangle.response.OrderTickerResponse;
import net.bigtangle.response.OutputsDetailsResponse;
import net.bigtangle.response.PermissionedAddressesResponse;
import net.bigtangle.response.TokenIndexResponse;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.signers.LocalTransactionSigner;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.MonetaryFormat;
import net.bigtangle.utils.OkHttp3Util;

/**
 * <p>
 * A Wallet  provide service for blocks and transactions
 * that send and receive value from those keys. Using these, it is able to
 * create new transactions that spend the recorded transactions, and this is the
 * fundamental operation of the protocol.
 * </p>
 * 
 * <p>
 * Wallets can be serialized using protocol buffers.
 * </p>
 */

public class Wallet extends WalletBase {

	private static final Logger log = LoggerFactory.getLogger(Wallet.class);
 
	/**
	 * Creates a wallet that tracks payments to and from the HD key hierarchy rooted
	 * by the given watching key. A watching key corresponds to account zero in the
	 * recommended BIP32 key hierarchy.
	 */
	public static Wallet fromKeys(NetworkParameters params, List<PQKey> keys) {
		for (PQKey key : keys)
			checkArgument(!(key instanceof DeterministicKey));

		KeyChainGroup group = new KeyChainGroup(params);
		group.importKeys(keys);
		return new Wallet(params, group);
	}

	public Wallet(NetworkParameters params) {
		this(params, new KeyChainGroup(params), null);
	}

	/*
	 * Creates a wallet containing a given set of keys. All further keys will be
	 * derived from the oldest key.
	 */
	public static Wallet fromKeys(NetworkParameters params, PQKey key) {

		return fromKeys(params, key, null);
	}

	public static Wallet fromKeys(NetworkParameters params, PQKey key, String url) {

		checkArgument(!(key instanceof DeterministicKey));
		List<PQKey> keys = new ArrayList<>();
		keys.add(key);
		KeyChainGroup group = new KeyChainGroup(params);
		group.importKeys(keys);
		return new Wallet(params, group, url);
	}

	public Wallet(NetworkParameters params, KeyChainGroup keyChainGroup) {
		this(params, keyChainGroup, null);
	}

	private Wallet(NetworkParameters params, KeyChainGroup keyChainGroup, String url) {

		this.params = params;
		this.keyChainGroup = checkNotNull(keyChainGroup);
		if (params.getId().equals(NetworkParameters.ID_UNITTESTNET))
			this.keyChainGroup.setLookaheadSize(5); // Cut down excess
													// computation for unit
													// tests.
		// If this keyChainGroup was created fresh just now (new wallet), make
		// HD so a backup can be made immediately
		// without having to call current/freshReceiveKey. If there are already
		// keys in the chain of any kind then
		// we're probably being deserialized so leave things alone: the API user
		// can upgrade later.
		if (this.keyChainGroup.numKeys() == 0)
			this.keyChainGroup.createAndActivateNewHDChain();

		signers = new ArrayList<>();
		addTransactionSigner(new LocalTransactionSigner());
		if (url == null) {
			this.serverPool = new ServerPool(params);
		} else {
			setServerURL(url);
		}
	}

 
	// All Spend Candidates as List<TransactionOutput>
	public List<FreeStandingTransactionOutput> calculateAllSpendCandidates(KeyParameter aesKey, boolean multisigns)
			throws IOException {

		List<FreeStandingTransactionOutput> candidates = new ArrayList<>();
		for (UTXO output : calculateAllSpendCandidatesUTXO(aesKey, multisigns)) {
			candidates.add(new FreeStandingTransactionOutput(this.params, output));
		}
		return candidates;

	}

	/*
	 * spendpending has timeout for 5 minute return false, if there is spendpending
	 * and timeout not
	 */
	public boolean checkSpendpending(UTXO output) {
		if (output.isSpendPending()) {
			return (System.currentTimeMillis() - output.getSpendPendingTime()) > SPENTPENDINGTIMEOUT;
		}
		return true;

	}

	// All Spend Candidates as List<UTXO>
	public List<UTXO> calculateAllSpendCandidatesUTXO(KeyParameter aesKey, boolean multisigns) throws IOException {

		List<UTXO> candidates = new ArrayList<>();
		List<String> pubKeyHashs = new ArrayList<>();
		for (PQKey ecKey : walletKeys(aesKey)) {
			pubKeyHashs.add(Utils.HEX.encode(ecKey.getPubKeyHash()));
		}
		byte[] response = OkHttp3Util.post(getServerURL() + ReqCmd.getOutputs.name(),
				Json.jsonmapper().writeValueAsString(pubKeyHashs).getBytes(StandardCharsets.UTF_8));
		GetOutputsResponse getOutputsResponse = Json.jsonmapper().readValue(response, GetOutputsResponse.class);
		for (UTXO output : getOutputsResponse.getOutputs()) {
			if (checkSpendpending(output)) {
				if (multisigns) {
					candidates.add(output);
				} else {
					if (!output.isMultiSig()) {
						candidates.add(output);
					}
				}
			}
		}
		Collections.shuffle(candidates);
		return candidates;

	}


	

	public Block saveToken(TokenInfo tokenInfo, Coin basecoin, PQKey ownerKey, KeyParameter aesKey, byte[] pubKeyTo,
			MemoInfo memoInfo) throws Exception {
		final Token token = tokenInfo.getToken();

		if (Utils.isBlank(token.getDomainNameBlockHash()) && Utils.isBlank(tokenInfo.getToken().getDomainName())) {
			final String domainname = token.getDomainName();
			GetDomainTokenResponse getDomainBlockHashResponse = this.getDomainNameBlockHash(domainname);
			Token domainNameBlockHash = getDomainBlockHashResponse.getdomainNameToken();
			token.setDomainNameBlockHash(domainNameBlockHash.getBlockHashHex());
			token.setDomainName(domainNameBlockHash.getTokenname());
		}

		if (Utils.isBlank(token.getDomainNameBlockHash()) && !Utils.isBlank(tokenInfo.getToken().getDomainName())) {
			Token domain = getDomainNameBlockHash(tokenInfo.getToken().getDomainName()).getdomainNameToken();
			token.setDomainNameBlockHash(domain.getBlockHashHex());

		}

		List<MultiSignAddress> multiSignAddresses = tokenInfo.getMultiSignAddresses();
		PermissionedAddressesResponse permissionedAddressesResponse = this.getPrevTokenMultiSignAddressList(token);
		if (permissionedAddressesResponse != null && permissionedAddressesResponse.getMultiSignAddresses() != null
				&& !permissionedAddressesResponse.getMultiSignAddresses().isEmpty()) {
			if (Utils.isBlank(token.getDomainName())) {
				token.setDomainName(permissionedAddressesResponse.getDomainName());
			}

			for (MultiSignAddress multiSignAddress : permissionedAddressesResponse.getMultiSignAddresses()) {
				final String pubKeyHex = multiSignAddress.getPubKeyHex();
				final String tokenid = token.getTokenid();
				multiSignAddresses.add(new MultiSignAddress(tokenid, "", pubKeyHex, 0));
			}
			// tokenInfo.setMultiSignAddresses(multiSignAddresses);
		}

		// +1 for domain name or super domain
		token.setSignnumber(token.getSignnumber() + 1);
		Block block = fetchTipBlock();
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);
		block.addCoinbaseTransaction(pubKeyTo, basecoin, tokenInfo, memoInfo);

		Transaction transaction = block.getTransactions().get(0);

		Sha256Hash sighash = transaction.getHash();

		SignatureBundle party1Signature = ownerKey.sign(sighash, aesKey);
		byte[] buf1 = party1Signature.encodeToDER();

		List<MultiSignBy> multiSignBies = new ArrayList<>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(ownerKey.toAddress(params).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(ownerKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

		// add fee transaction
		if (getFee()) {
			block.addTransaction(feeTransaction(aesKey));
		}
		return adjustSolveAndSign(block);
	}

	private Block adjustSolveAndSign(Block block) throws IOException {
		// save block
		try {
			OkHttp3Util.post(getServerURL() + ReqCmd.signToken.name(), block.bitcoinSerialize());
			return block;
		} catch (ConnectException e) {
			serverConnectException();
			throw e;
		}

	}

	private void serverConnectException() {
		this.params.serverSeeds();
	}

	// pay the BIGTANGLE_TOKENID from the list HashMap<String, Long>
	// giveMoneyResult of
	// address and amount and return the remainder back to fromkey.

	public Transaction payMoneyToECKeyList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, String memo)
			throws IOException, InsufficientMoneyException {

		return payMoneyToECKeyList(aesKey, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID, memo,
				calculateAllSpendCandidates(aesKey, false));
	}

	public Transaction payMoneyToECKeyList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid,
			String memo) throws IOException, InsufficientMoneyException {

		return payMoneyToECKeyList(aesKey, giveMoneyResult, tokenid, memo, calculateAllSpendCandidates(aesKey, false));
	}

	public Transaction payMoneyToECKeyList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, String memo,
			List<FreeStandingTransactionOutput> coinList) throws IOException, InsufficientMoneyException {

		return payMoneyToECKeyList(aesKey, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID, memo, coinList);
	}

	public Transaction payMoneyToECKeyList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid,
			String memo, List<FreeStandingTransactionOutput> coinList)
			throws IOException, InsufficientMoneyException {
		try {
			return payToList(aesKey, giveMoneyResult, tokenid, memo, filterTokenid(tokenid, coinList));
		} catch (InsufficientMoneyException e) {
			log.debug(" InsufficientMoneyException  {} ", giveMoneyResult);
		}
		BigInteger requested = BigInteger.ZERO;
		for (BigInteger value : giveMoneyResult.values()) {
			requested = requested.add(value);
		}
		BigInteger available = sumOutputValues(coinList);
		BigInteger deficit = requested.subtract(available);
		String tokenHex = Utils.HEX.encode(tokenid);
		String info = "token=" + tokenHex + " requested=" + requested + " available=" + available + " deficit="
				+ deficit + " recipients=" + giveMoneyResult.size();
		logInsufficientMoney("payMoneyToECKeyList", info, aesKey, coinList);
		throw new InsufficientMoneyException("InsufficientMoneyException " + giveMoneyResult);
	}

	// pay the tokenid from the list HashMap<String, Long> giveMoneyResult of
	// address and amount and return the remainder back to fromkey.
	public Transaction payToList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid,
			String memo) throws IOException, InsufficientMoneyException {
		return payToList(aesKey, giveMoneyResult, tokenid, memo, calculateAllSpendCandidates(aesKey, false));
	}

	public List<Transaction> payFromList(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo)
			throws IOException, InsufficientMoneyException {
		return payFromList(aesKey, destination, amount, memo, calculateAllSpendCandidates(aesKey, false));
	}

	public List<Transaction> payFromList(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo,
			List<FreeStandingTransactionOutput> coinList) throws IOException, InsufficientMoneyException {
		return payFromList(aesKey, destination, amount, memo, coinList,
				NetworkParameters.TARGET_MAX_BLOCKS_IN_REWARD / 4);
	}

	private List<Transaction> payFromList(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo,
			List<FreeStandingTransactionOutput> coinList, int split) throws IOException, InsufficientMoneyException {

		List<FreeStandingTransactionOutput> coinTokenList = filterTokenid(amount.getTokenid(), coinList);

		Coin sum = sum(coinTokenList);
		if (sum.compareTo(amount) < 0) {
			Coin deficit = amount.subtract(sum);
			String info = "token=" + Utils.HEX.encode(amount.getTokenid()) + " required=" + amount + " available="
					+ sum + " deficit=" + deficit + " destination=" + destination;
			logInsufficientMoney("payFromList", info, aesKey, coinTokenList);
			throw new InsufficientMoneyException("to pay " + amount + " account sum: " + sum);
		}
		List<List<FreeStandingTransactionOutput>> parts = chopped(coinTokenList, split);

		List<Transaction> re = new ArrayList<>();
		Coin payAmount = amount;
		for (List<FreeStandingTransactionOutput> part : parts) {
			Coin canPay = sum(part);
			Transaction tx = payFromListNoSplit(aesKey, destination, payAmount, memo, part);
			re.add(tx);
			if (canPay.compareTo(payAmount) >= 0) {
				break;
			}
			payAmount = payAmount.subtract(canPay);
		}

		for (Transaction tx : re) {
			submitTransaction(tx);
			if (getFee() && !amount.isBIG()) {
				submitTransaction(feeTransaction(aesKey, coinList));
			}
		}
		return re;
	}

	public Coin sum(List<FreeStandingTransactionOutput> coinList) {
		Coin sum = new Coin(0, coinList.get(0).getValue().getTokenid());
		for (FreeStandingTransactionOutput u : coinList) {
			sum = u.getValue().add(sum);
		}
		return sum;
	}

	// List<UTXO> coinList may not pay the amount, the rest to be paid is
	// restAmount
	private Transaction payFromListNoSplit(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo,
			List<FreeStandingTransactionOutput> coinList) throws InsufficientMoneyException {

		return payFromListNoSplitTransaction(aesKey, destination, amount, memo, coinList);

	}

	private Transaction payFromListNoSplitTransaction(KeyParameter aesKey, String destination, Coin amount,
			MemoInfo memo, List<FreeStandingTransactionOutput> coinList) throws InsufficientMoneyException {
		Transaction multispent = new Transaction(params);
		multispent.setMemo(memo);
		multispent.addOutput(amount, Address.fromBase58(params, destination));
		Coin restAmount = amount.negate();
		PQKey beneficiary = null;
		if (getFee() && amount.isBIG()) {
			restAmount = restAmount.add(Coin.FEE_DEFAULT.negate());
		}

		List<FreeStandingTransactionOutput> coinTokenList = filterTokenid(restAmount.getTokenid(), coinList);

		for (FreeStandingTransactionOutput spendableOutput : coinTokenList) {

			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			restAmount = spendableOutput.getValue().add(restAmount);
			multispent.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!restAmount.isNegative()) {
				if (restAmount.isPositive()) {
					multispent.addOutput(restAmount, beneficiary);
				}
				break;
			}
		}
		if (beneficiary == null || restAmount.isNegative()) {
			Coin deficit = restAmount.isNegative() ? restAmount.negate() : restAmount;
			String info = "destination=" + destination + " requested=" + amount + " remaining=" + restAmount
					+ " deficit=" + deficit + " inputs=" + coinTokenList.size();
			logInsufficientMoney("payFromListNoSplitTransaction", info, aesKey, coinTokenList);
			throw new InsufficientMoneyException(amount + " outputs size= " + coinTokenList.size());
		}

		signTransaction(multispent, aesKey);
		return multispent;
	}

	public Transaction payToScript(KeyParameter aesKey, Coin amount, MemoInfo memo, Script script)
			throws InsufficientMoneyException, IOException {

		List<FreeStandingTransactionOutput> coinList = calculateAllSpendCandidates(aesKey, false);

		Transaction multispent = new Transaction(params);
		multispent.setMemo(memo);
		multispent.addOutput(amount, script);
		Coin restAmount = amount.negate();
		PQKey beneficiary = null;
		if (getFee() && amount.isBIG()) {
			restAmount = restAmount.add(Coin.FEE_DEFAULT.negate());
		}

		List<FreeStandingTransactionOutput> coinTokenList = filterTokenid(restAmount.getTokenid(), coinList);

		for (FreeStandingTransactionOutput spendableOutput : coinTokenList) {

			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			restAmount = spendableOutput.getValue().add(restAmount);
			multispent.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!restAmount.isNegative()) {
				if (restAmount.isPositive()) {
					multispent.addOutput(restAmount, beneficiary);
				}
				break;
			}
		}
		if (beneficiary == null || restAmount.isNegative()) {
			Coin deficit = restAmount.isNegative() ? restAmount.negate() : restAmount;
			String info = "scriptPayment requested=" + amount + " remaining=" + restAmount + " deficit=" + deficit
					+ " memo=" + memo;
			logInsufficientMoney("payToScript", info, aesKey, coinTokenList);
			throw new InsufficientMoneyException(amount + " outputs size= " + coinTokenList.size());
		}

		signTransaction(multispent, aesKey);

		submitTransaction(multispent);
		if (getFee() && !amount.isBIG()) {
			submitTransaction(feeTransaction(aesKey, coinList));
		}
		return multispent;
	}

	// chops a list into non-view sublists of length L

	private Block fetchTipBlock() throws IOException {
		return params.getDefaultSerializer().makeBlock(getTipData());
	}

	private byte[] getTipData() throws IOException {
		HashMap<String, String> requestParam = new HashMap<>();
		return OkHttp3Util.postAndGetBlock(getServerURL() + ReqCmd.getTip,
				Json.jsonmapper().writeValueAsString(requestParam));
	}
	public static <T> List<List<T>> chopped(List<T> list, final int L) {
		List<List<T>> parts = new ArrayList<>();
		final int N = list.size();
		for (int i = 0; i < N; i += L) {
			parts.add(new ArrayList<>(list.subList(i, Math.min(N, i + L))));
		}
		return parts;
	}

	public Transaction payToList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid,
			String memo, List<FreeStandingTransactionOutput> coinList) throws IOException, InsufficientMoneyException {

		if (giveMoneyResult.isEmpty()) {
			return null;
		}
		Transaction multispent = payToListTransaction(aesKey, giveMoneyResult, tokenid, memo, coinList);

		submitTransaction(multispent);
		if (getFee() && !Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, tokenid)) {
			submitTransaction(feeTransaction(aesKey, coinList));
		}
		return multispent;
	}

	public Transaction payToListTransaction(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult,
			byte[] tokenid, String memo, List<FreeStandingTransactionOutput> coinList)
			throws InsufficientMoneyException {
		Coin summe = Coin.valueOf(0, tokenid);
		Transaction multispent = new Transaction(params);
		multispent.setMemo(new MemoInfo(memo));
		for (Map.Entry<String, BigInteger> entry : giveMoneyResult.entrySet()) {
			Coin a = new Coin(entry.getValue(), tokenid);
			Address address = Address.fromBase58(params, entry.getKey());
			multispent.addOutput(a, address);
			summe = summe.add(a);
		}
		Coin amount = summe.negate();

		if (getFee() && amount.isBIG()) {
			amount = amount.add(Coin.FEE_DEFAULT.negate());
		}

		PQKey beneficiary = null;
		// filter only for tokenid
		List<FreeStandingTransactionOutput> coinListTokenid = filterTokenid(tokenid, coinList);
		for (FreeStandingTransactionOutput spendableOutput : coinListTokenid) {
			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			amount = spendableOutput.getValue().add(amount);
			multispent.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!amount.isNegative()) {
				multispent.addOutput(amount, beneficiary);
				break;
			}
		}
		if (beneficiary == null || amount.isNegative()) {
			Coin deficit = amount.isNegative() ? amount.negate() : amount;
			String info = "payToList total=" + summe + " remainder=" + amount + " deficit=" + deficit
					+ " recipients=" + giveMoneyResult.size();
			logInsufficientMoney("payToListTransaction", info, aesKey, coinListTokenid);
			throw new InsufficientMoneyException(summe + " outputs size= " + coinListTokenid.size());
		}

		signTransaction(multispent, aesKey);
		return multispent;
	}

	public Transaction feeTransaction(KeyParameter aesKey) throws InsufficientMoneyException, IOException {
		return feeTransaction(aesKey, calculateAllSpendCandidates(aesKey, false));
	}

	public Transaction feeTransaction(KeyParameter aesKey, List<FreeStandingTransactionOutput> coinList)
			throws InsufficientMoneyException {

		Transaction spent = new Transaction(params);
		spent.setMemo(new MemoInfo("fee"));
		// Fixed fee in BIG
		Coin amount = Coin.FEE_DEFAULT.negate();
		PQKey beneficiary = null;
		// filter only for NetworkParameters.BIGTANGLE_TOKENID
		List<FreeStandingTransactionOutput> coinListTokenid = filterTokenid(NetworkParameters.BIGTANGLE_TOKENID,
				coinList);
		for (FreeStandingTransactionOutput spendableOutput : coinListTokenid) {
			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			amount = spendableOutput.getValue().add(amount);
			spent.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!amount.isNegative()) {
				spent.addOutput(amount, beneficiary);
				break;
			}
		}
		if (beneficiary == null || amount.isNegative()) {
			Coin deficit = amount.isNegative() ? amount.negate() : amount;
			String info = "feePayment required=" + Coin.FEE_DEFAULT + " remainder=" + amount + " deficit=" + deficit;
			logInsufficientMoney("feeTransaction", info, aesKey, coinListTokenid);
			throw new InsufficientMoneyException(Coin.FEE_DEFAULT + " outputs size= " + coinListTokenid.size());
		}

		signTransaction(spent, aesKey);
		return spent;
	}

	// check the token id is on the server
	// throw NoTokenException
	public Token checkTokenId(String tokenid) throws IOException, NoTokenException {
		HashMap<String, Object> requestParam = new HashMap<>();
		requestParam.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		GetTokensResponse token = Json.jsonmapper().readValue(resp, GetTokensResponse.class);
		if (token.getTokens() == null || token.getTokens().isEmpty()) {
			throw new NoTokenException();
		}
		return token.getTokens().get(0);
	}

	/*
	 * It must use BigInteger to calculation to avoid overflow. Order can handle
	 * only Long
	 */
	public BigInteger totalAmount(long price, long amount, int tokenDecimal, boolean allowRemainder) {

		BigInteger[] rearray = BigInteger.valueOf(price).multiply(BigInteger.valueOf(amount))
				.divideAndRemainder(BigInteger.valueOf(LongMath.checkedPow(10, tokenDecimal)));
		BigInteger re = rearray[0];
		BigInteger remainder = rearray[1];
		if (remainder.compareTo(BigInteger.ZERO) > 0 && !allowRemainder) {
			// This remainder will cut
			throw new OrderWithRemainderException("Invalid price and quantity value with remainder " + remainder);
		}
		if (re.compareTo(BigInteger.ONE) < 0 || re.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
			throw new InvalidTransactionDataException("Invalid target total value: " + re);
		}
		return re;
	}

	/*
	 * Buy order is defined as offervalue = targetValue * price / 10**targetDecimal
	 * offerToken=orderBaseToken
	 * 
	 */
	public Transaction buyOrder(KeyParameter aesKey, String targetTokenId, long buyPrice, long targetValue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder)
			throws IOException, InsufficientMoneyException, NoTokenException {
		Token targetToken = checkTokenId(targetTokenId);
		return buyOrder(aesKey, targetToken, buyPrice, targetValue, validToTime, validFromTime, orderBaseToken,
				allowRemainder);
	}

	public Transaction buyOrder(KeyParameter aesKey, Token targetToken, long buyPrice, long targetValue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder)
			throws IOException, InsufficientMoneyException, NoTokenException {

		if (targetToken.getTokenid().equals(orderBaseToken))
			throw new OrderImpossibleException("buy token is base token ");

		List<FreeStandingTransactionOutput> candidates = calculateAllSpendCandidates(aesKey, false);

		return buyOrder(aesKey, targetToken, buyPrice, targetValue, validToTime, validFromTime, orderBaseToken,
				allowRemainder, candidates);
	}

	public Transaction buyOrder(KeyParameter aesKey, Token targetToken, long buyPrice, long targetValue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates)
			throws IOException, InsufficientMoneyException, NoTokenException {

		return buyOrderDo(aesKey, targetToken, buyPrice, targetValue, validToTime, validFromTime, orderBaseToken,
				allowRemainder, candidates);
	}

	public Transaction buyOrderDo(KeyParameter aesKey, Token targetToken, long buyPrice, long targetValue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates)
			throws IOException, InsufficientMoneyException, NoTokenException {

		if (targetToken.getTokenid().equals(orderBaseToken))
			throw new OrderImpossibleException("buy token is base token ");
		Integer priceshift = params.getOrderPriceShift(orderBaseToken);
		// Burn orderBaseToken to buy
		Coin toBePaid = new Coin(
				totalAmount(buyPrice, targetValue, targetToken.getDecimals() + priceshift, allowRemainder),
				Utils.HEX.decode(orderBaseToken)).negate();
		if (getFee() && NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(orderBaseToken)) {
			toBePaid = toBePaid.add(Coin.FEE_DEFAULT.negate());
		}
		Transaction tx = new Transaction(params);

		PQKey beneficiary = null;

		for (FreeStandingTransactionOutput spendableOutput : candidates) {
			if (orderBaseToken.equals(spendableOutput.getUTXO().getTokenId())) {
				beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
				toBePaid = spendableOutput.getValue().add(toBePaid);
				tx.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
				if (!toBePaid.isNegative()) {
					tx.addOutput(toBePaid, beneficiary);
					break;
				}
			}
		}
		if (beneficiary == null || toBePaid.isNegative()) {
			byte[] baseTokenId = Utils.HEX.decode(orderBaseToken);
			List<FreeStandingTransactionOutput> spendableCandidates = candidates == null ? Collections.emptyList()
					: candidates;
			List<FreeStandingTransactionOutput> baseOutputs = filterTokenid(baseTokenId, spendableCandidates);
			Coin requiredAmount = toBePaid.negate();
			Coin availableCoin = sumOutputs(baseTokenId, baseOutputs);
			Coin deficitCoin = requiredAmount.subtract(availableCoin);
			String info = "orderBaseToken=" + orderBaseToken + " required=" + requiredAmount + " available="
					+ availableCoin + " deficit=" + deficitCoin + " price=" + buyPrice + " targetValue="
					+ targetValue;
			logInsufficientMoney("buyOrderDo", info, aesKey, baseOutputs);
			throw new InsufficientMoneyException(orderBaseToken);
		}

		OrderOpenInfo info = new OrderOpenInfo(targetValue, targetToken.getTokenid(), beneficiary.getPubKey(),
				validToTime, validFromTime, Side.BUY, beneficiary.toAddress(params).toBase58(), orderBaseToken,
				buyPrice,
				totalAmount(buyPrice, targetValue, targetToken.getDecimals() + priceshift, allowRemainder).longValue(),
				orderBaseToken);
		tx.setData(info.toByteArray());
		tx.setDataClassName("OrderOpen");
		signTransaction(tx, aesKey);

		submitTransaction(tx);
		if (getFee() && !NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(orderBaseToken)) {
			submitTransaction(feeTransaction(aesKey, candidates));
		}
		return tx;
	}

	/*
	 * Sell Order is defined as targetvalue = offervalue * price / 10**offerDecimal
	 * targetToken=orderBaseToken
	 */
	public Transaction sellOrder(KeyParameter aesKey, String offerTokenId, long sellPrice, long offervalue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder)
			throws IOException, NoTokenException, InsufficientMoneyException {
		Token t = checkTokenId(offerTokenId);
		return sellOrder(aesKey, t, sellPrice, offervalue, validToTime, validFromTime, orderBaseToken, allowRemainder);
	}

	public Transaction sellOrder(KeyParameter aesKey, Token t, long sellPrice, long offervalue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder)
			throws IOException, InsufficientMoneyException {
		if (t.getTokenid().equals(orderBaseToken))
			throw new OrderImpossibleException("sell token is not allowed as base token ");

		List<FreeStandingTransactionOutput> candidates = calculateAllSpendCandidates(aesKey, false);

		return sellOrder(aesKey, t, sellPrice, offervalue, validToTime, validFromTime, orderBaseToken, allowRemainder,
				candidates);
	}

	public Transaction sellOrder(KeyParameter aesKey, Token t, long sellPrice, long offervalue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates)
			throws IOException, InsufficientMoneyException {
		return sellOrderDo(aesKey, t, sellPrice, offervalue, validToTime, validFromTime, orderBaseToken, allowRemainder,
				candidates);
	}

	public Transaction sellOrderDo(KeyParameter aesKey, Token t, long sellPrice, long offervalue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates) throws IOException, InsufficientMoneyException {
		if (t.getTokenid().equals(orderBaseToken))
			throw new OrderImpossibleException("sell token is not allowed as base token ");
		Integer priceshift = params.getOrderPriceShift(orderBaseToken);
		// Burn tokens to sell
		Coin myCoin = Coin.valueOf(offervalue, t.getTokenid()).negate();

		if (getFee() && NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(t.getTokenid())) {
			myCoin = myCoin.add(Coin.FEE_DEFAULT.negate());
		}

		Transaction tx = new Transaction(params);

		PQKey beneficiary = null;
		for (FreeStandingTransactionOutput spendableOutput : candidates) {
			if (t.getTokenid().equals(spendableOutput.getUTXO().getTokenId())) {
				beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
				myCoin = spendableOutput.getValue().add(myCoin);
				tx.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
				if (!myCoin.isNegative()) {
					tx.addOutput(myCoin, beneficiary);
					break;
				}
			}
		}
		if (beneficiary == null || myCoin.isNegative()) {
			byte[] sellTokenBytes = Utils.HEX.decode(t.getTokenid());
			List<FreeStandingTransactionOutput> spendableCandidates = candidates == null ? Collections.emptyList()
					: candidates;
			List<FreeStandingTransactionOutput> sellOutputs = filterTokenid(sellTokenBytes, spendableCandidates);
			Coin requiredAmount = Coin.valueOf(offervalue, t.getTokenid());
			if (getFee() && NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(t.getTokenid())) {
				requiredAmount = requiredAmount.add(Coin.FEE_DEFAULT);
			}
			Coin availableCoin = sumOutputs(sellTokenBytes, sellOutputs);
			Coin deficitCoin = requiredAmount.subtract(availableCoin);
			String info = "sellToken=" + t.getTokenid() + " required=" + requiredAmount + " available="
					+ availableCoin + " deficit=" + deficitCoin + " price=" + sellPrice + " offervalue="
					+ offervalue;
			logInsufficientMoney("sellOrderDo", info, aesKey, sellOutputs);
			throw new InsufficientMoneyException("");
		}
		// get the base token
		BigInteger targetvalue = totalAmount(sellPrice, offervalue, t.getDecimals() + priceshift, allowRemainder);
		if (targetvalue.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
			throw new InvalidTransactionDataException("Invalid  max: " + targetvalue + " > " + Long.MAX_VALUE);
		}

		OrderOpenInfo info = new OrderOpenInfo(targetvalue.longValue(), orderBaseToken, beneficiary.getPubKey(),
				validToTime, validFromTime, Side.SELL, beneficiary.toAddress(params).toBase58(), orderBaseToken,
				sellPrice, offervalue, t.getTokenid());
		tx.setData(info.toByteArray());
		tx.setDataClassName("OrderOpen");

		signTransaction(tx, aesKey);

		submitTransaction(tx);
		if (getFee() && !NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(t.getTokenid())) {
			submitTransaction(feeTransaction(aesKey, candidates));
		}
		return tx;
	}

	public Transaction cancelOrder(Sha256Hash orderblockhash, KeyParameter aesKey, String address)
			throws IOException, InsufficientMoneyException, NoDataException {
		PQKey legitimatingKey = null;
		for (PQKey ecKey : walletKeys(aesKey)) {
			if (address.equals(ecKey.toAddress(params).toString())) {
				legitimatingKey = ecKey;
				break;
			}
		}
		if (legitimatingKey == null) {
			throw new NoDataException(" no keys ");
		}
		// Make an order op
		Transaction tx = new Transaction(params);
		OrderCancelInfo info = new OrderCancelInfo(orderblockhash);
		tx.setData(info.toByteArray());
		tx.setDataClassName("OrderCancelInfo");

		// Legitimate it by signing
		Sha256Hash sighash1 = tx.getHash();
		SignatureBundle party1Signature = legitimatingKey.sign(sighash1, null);
		byte[] buf1 = party1Signature.encodeToDER();
		tx.setDataSignature(buf1);

		submitTransaction(tx);
		if (getFee())
			submitTransaction(feeTransaction(aesKey, calculateAllSpendCandidates(aesKey, false)));
		return tx;

	}

	public Transaction contractEventCancel(Sha256Hash eventblockhash, KeyParameter aesKey, String address)
			throws IOException, InsufficientMoneyException, NoDataException {
		PQKey legitimatingKey = null;
		for (PQKey ecKey : walletKeys(aesKey)) {
			if (address.equals(ecKey.toAddress(params).toString())) {
				legitimatingKey = ecKey;
				break;
			}
		}
		if (legitimatingKey == null) {
			throw new NoDataException(" no keys ");
		}
		// Make an order op
		Transaction tx = new Transaction(params);
		ContractEventCancelInfo info = new ContractEventCancelInfo(eventblockhash);
		tx.setData(info.toByteArray());
		tx.setDataClassName("ContractEventCancelInfo");

		// Legitimate it by signing
		Sha256Hash sighash1 = tx.getHash();
		SignatureBundle party1Signature = legitimatingKey.sign(sighash1, null);
		byte[] buf1 = party1Signature.encodeToDER();
		tx.setDataSignature(buf1);

		submitTransaction(tx);
		if (getFee())
			submitTransaction(feeTransaction(aesKey, calculateAllSpendCandidates(aesKey, false)));
		return tx;

	}

	public Transaction payContract(KeyParameter aesKey, String tokenId, BigInteger payAmount, Long validToTime,
			Long validFromTime, String contractTokenid)
			throws IOException, InsufficientMoneyException, NoTokenException {

		Coin amount = new Coin(payAmount, tokenId).negate();

		if (getFee() && NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(tokenId)) {
			amount = amount.add(Coin.FEE_DEFAULT.negate());
		}

		Transaction tx = new Transaction(params);
		List<FreeStandingTransactionOutput> coinList = calculateAllSpendCandidates(aesKey, false);
		PQKey beneficiary = null;
		for (FreeStandingTransactionOutput spendableOutput : filterTokenid(amount.getTokenid(), coinList)) {

			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			amount = spendableOutput.getValue().add(amount);
			tx.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!amount.isNegative()) {
				tx.addOutput(amount, beneficiary);
				break;
			}
		}
		if (beneficiary == null || amount.isNegative()) {
			Coin deficit = amount.isNegative() ? amount.negate() : amount;
			String info = "payContract token=" + tokenId + " required=" + deficit + " remainder=" + amount
					+ " outputs=" + coinList.size();
			logInsufficientMoney("payContract", info, aesKey, coinList);
			throw new InsufficientMoneyException(amount + " outputs size= " + coinList.size());

		}

		ContractEventInfo info = new ContractEventInfo(contractTokenid, payAmount, tokenId,
				beneficiary.toAddress(params).toBase58(), validToTime, validFromTime, "");
		tx.setData(info.toByteArray());
		tx.setDataClassName("ContractEventInfo");
		signTransaction(tx, aesKey);

		submitTransaction(tx);
		if (getFee() && !NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(tokenId)) {
			submitTransaction(feeTransaction(aesKey, coinList));
		}
		return tx;
	}

	public void submitTransaction(Transaction tx) throws IOException {
		try {
			OkHttp3Util.post(getServerURL() + ReqCmd.submitTransaction.name(), tx.bitcoinSerialize());
		} catch (ConnectException e) {
			this.serverPool.removeServer(getServerURL());
			throw e;
		}
	}



	private List<FreeStandingTransactionOutput> filterTokenid(byte[] tokenid, List<FreeStandingTransactionOutput> l) {
		List<FreeStandingTransactionOutput> re = new ArrayList<>();
		for (FreeStandingTransactionOutput u : l) {
			if (Arrays.equals(u.getValue().getTokenid(), tokenid)) {
				re.add(u);
			}
		}
		return re;
	}

	private void logInsufficientMoney(String context, String deficitInfo, KeyParameter aesKey,
			List<FreeStandingTransactionOutput> outputs) {
		log.info("[{}] insufficient money -> {}", context, deficitInfo);
		logOutputDetails(outputs);
		logWalletKeys(aesKey);
	}

	private void logOutputDetails(List<FreeStandingTransactionOutput> outputs) {
		if (outputs == null) {
			log.info("Spendable outputs snapshot unavailable");
			return;
		}
		log.info("Spendable outputs count: {}", outputs.size());
		for (FreeStandingTransactionOutput output : outputs) {
			log.info("Output summary -> blockHash:{} value:{} token:{} address:{}",
					output.getUTXO().getBlockHash(), output.getValue(), output.getUTXO().getTokenId(),
					output.getUTXO().getAddress());
		}
	}

	private void logWalletKeys(KeyParameter aesKey) {
		try {
			List<PQKey> keys = walletKeys(aesKey);
			if (keys == null || keys.isEmpty()) {
				log.info("Wallet keys unavailable (no keys returned)");
				return;
			}
			for (PQKey ecKey : keys) {
				log.info("Wallet key: {}", ecKey.toAddress(params));
			}
		} catch (Exception e) {
			log.info("Wallet keys unavailable ({})", e.getMessage());
		}
	}

	private BigInteger sumOutputValues(List<FreeStandingTransactionOutput> outputs) {
		BigInteger total = BigInteger.ZERO;
		if (outputs == null) {
			return total;
		}
		for (FreeStandingTransactionOutput output : outputs) {
			total = total.add(output.getValue().getValue());
		}
		return total;
	}

	private Coin sumOutputs(byte[] tokenid, List<FreeStandingTransactionOutput> outputs) {
		Coin total = Coin.valueOf(0, tokenid);
		if (outputs == null) {
			return total;
		}
		for (FreeStandingTransactionOutput output : outputs) {
			total = output.getValue().add(total);
		}
		return total;
	}

	public Transaction paySubtangle(KeyParameter aesKey, String outputStr, PQKey connectKey, Address toAddressInSubtangle,
			Coin coin, Address address) throws IOException {

		HashMap<String, Object> requestParam = new HashMap<>();
		requestParam.put("hexStr", outputStr);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getOutputByKey.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		OutputsDetailsResponse outputsDetailsResponse = Json.jsonmapper().readValue(resp, OutputsDetailsResponse.class);
		UTXO findOutput = outputsDetailsResponse.getOutputs();

		TransactionOutput spendableOutput = new FreeStandingTransactionOutput(params, findOutput);
		Transaction transaction = new Transaction(params);

		transaction.addOutput(coin, address);

		transaction.setToAddressInSubtangle(toAddressInSubtangle.getHash160());

		TransactionInput input = transaction.addInput(findOutput.getBlockHash(), spendableOutput);
		Sha256Hash sighash = transaction.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL,
				false);

		TransactionSignature tsrecsig = new TransactionSignature(connectKey.sign(sighash, aesKey),
				Transaction.SigHash.ALL, false);
		Script inputScript = ScriptBuilder.createInputScript(tsrecsig);
		input.setScriptSig(inputScript);

		submitTransaction(transaction);
		return transaction;
	}

	public PQKey getECKey(KeyParameter aesKey, String address) {

		List<PQKey> keys = walletKeys(aesKey);
		PQKey beneficiary;
		for (PQKey ecKey : keys) {
			if (address.equals(ecKey.toAddress(params).toString())) {
				beneficiary = ecKey;
				return beneficiary;
			}
		}
		throw new RuntimeException("no key in wallet is found for this address " + address);
	}

	public List<Transaction> pay(KeyParameter aesKey, String destination, Coin amount, String memo)
			throws IOException, InsufficientMoneyException {

		return payFromList(aesKey, destination, amount, new MemoInfo(memo));
	}

	public List<Transaction> pay(KeyParameter aesKey, Address destination, Coin amount, String memo)
			throws IOException, InsufficientMoneyException {

		return payFromList(aesKey, destination.toString(), amount, new MemoInfo(memo));
	}

	public List<Transaction> pay(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo)
			throws IOException, InsufficientMoneyException {

		return payFromList(aesKey, destination, amount, memo);
	}

	public Transaction createTransaction(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo)
			throws IOException, InsufficientMoneyException {

		return payFromListNoSplitTransaction(aesKey, destination, amount, memo,
				calculateAllSpendCandidates(aesKey, false));
	}

	public Transaction createTransaction(KeyParameter aesKey, List<FreeStandingTransactionOutput> candidates,
			String destination, Coin amount, String memo) throws InsufficientMoneyException {

		return payFromListNoSplitTransaction(aesKey, destination, amount, new MemoInfo(memo), candidates);
	}

	public Transaction createTransaction(KeyParameter aesKey, List<FreeStandingTransactionOutput> candidates,
			Address destination, Coin amount, String memo) throws InsufficientMoneyException {

		return payFromListNoSplitTransaction(aesKey, destination.toString(), amount, new MemoInfo(memo), candidates);
	}

	public Transaction payTransaction(List<Transaction> txs) throws IOException {
		for (Transaction tx : txs) {
			submitTransaction(tx);
		}
		return txs.isEmpty() ? null : txs.get(0);
	}

	/*
	 * pay all small coins in a wallet to one destination. This destination can be
	 * in same wallet.
	 */
	public List<Transaction> payPartsToOne(KeyParameter aesKey, String destination, byte[] tokenid, String memo)
			throws IOException, InsufficientMoneyException {

		return payPartsToOne(aesKey, destination, tokenid, memo, BigInteger.ZERO);
	}

	/*
	 * pay all small coins in a wallet to one destination. This destination can be
	 * in same wallet.
	 */
	public List<Transaction> payPartsToOne(KeyParameter aesKey, String destination, byte[] tokenid, String memo,
			BigInteger low) throws IOException, InsufficientMoneyException {

		List<UTXO> l = calculateAllSpendCandidatesUTXO(aesKey, false);
		Coin summe = Coin.valueOf(0, tokenid);
		int size = 0;
		for (UTXO u : l) {
			if (Arrays.equals(u.getValue().getTokenid(), tokenid)
					&& size < NetworkParameters.MAX_DEFAULT_BLOCK_SIZE / 10000) {
				if (low.signum() == 0 || (low.signum() > 0 && u.getValue().getValue().compareTo(low) > 0)) {
					summe = summe.add(u.getValue());
					size += 1;
				}
			}
		}
		//
		if (getFee() && Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, tokenid)) {
			summe = summe.subtract(Coin.FEE_DEFAULT);
		}
		return pay(aesKey, destination, summe, new MemoInfo(memo));
	}

	public Transaction saveUserdata(PQKey userKey, Transaction transaction, boolean encrypt, KeyParameter aesKey)
			throws IOException, InsufficientMoneyException, InvalidCipherTextException {
		if (encrypt) {
			byte[] cipher = ECIESCoder.encrypt(userKey.getPubKeyPoint(), transaction.getData());
			transaction.setData(cipher);
		}

		Sha256Hash sighash = transaction.getHash();
		SignatureBundle party1Signature = userKey.sign(sighash);
		byte[] buf1 = party1Signature.encodeToDER();

		List<MultiSignBy> multiSignBies = new ArrayList<>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setAddress(userKey.toAddress(params).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(userKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignBies));

		submitTransaction(transaction);
		if (getFee()) {
			submitTransaction(feeTransaction(aesKey));
		}
		return transaction;
	}

	public UserSettingDataInfo getUserSettingDataInfo(PQKey userKey, boolean encrypt)
			throws IOException, InvalidCipherTextException {
		HashMap<String, String> requestParam0 = new HashMap<>();
		requestParam0.put("dataclassname", DataClassName.UserSettingDataInfo.name());
		requestParam0.put("pubKey", Utils.HEX.encode(userKey.getPubKey()));
		byte[] buf = OkHttp3Util.postAndGetBlock(getServerURL() + ReqCmd.getUserData.name(),
				Json.jsonmapper().writeValueAsString(requestParam0));
		UserSettingDataInfo userSettingDataInfo = null;
		if (buf != null && buf.length > 0) {
			if (encrypt) {
				byte[] decryptedPayload = ECIESCoder.decrypt(userKey.getPrivKey(), buf);
				userSettingDataInfo = new UserSettingDataInfo().parse(decryptedPayload);
			} else {
				userSettingDataInfo = new UserSettingDataInfo().parse(buf);
			}

		}
		return userSettingDataInfo;

	}

	
	public void publishDomainName(PQKey ownerKey, String tokenid, String tokenname, KeyParameter aesKey,
			String description) throws Exception {
		GetDomainTokenResponse getDomainBlockHashResponse = this.getDomainNameBlockHash(tokenname);
		Token domainName = getDomainBlockHashResponse.getdomainNameToken();

		List<PQKey> walletKeys = new ArrayList<>();
		walletKeys.add(ownerKey);

		final int signnumber = walletKeys.size();
		this.publishDomainName(walletKeys, ownerKey, tokenid, tokenname, domainName, aesKey, description, signnumber);
	}

	public void publishDomainName(List<PQKey> signKeys, PQKey ownerKey, String tokenid, String tokenname,
			KeyParameter aesKey, String description) throws Exception {
		GetDomainTokenResponse getDomainBlockHashResponse = this.getDomainNameBlockHash(tokenname);
		Token domainNameBlockHash = getDomainBlockHashResponse.getdomainNameToken();
		final int signnumber = signKeys.size();
		this.publishDomainName(signKeys, ownerKey, tokenid, tokenname, domainNameBlockHash, aesKey, description,
				signnumber);
	}

	public void publishDomainName(List<PQKey> multiSigns, PQKey ownerKey, String tokenid, String tokenname,
			Token domainNameBlockHash, KeyParameter aesKey, String description, int signnumber) throws Exception {

		TokenIndexResponse tokenIndexResponse = this.getServerCalTokenIndex(tokenid);

		long tokenindex_ = tokenIndexResponse.getTokenindex();

		Token tokens = Token.buildDomainnameTokenInfo(true, tokenIndexResponse.getBlockhash(), tokenid, tokenname,
				description, signnumber, tokenindex_, false, domainNameBlockHash.getTokenname(),
				domainNameBlockHash.getBlockHashHex());
		TokenInfo tokenInfo = new TokenInfo();
		tokenInfo.setToken(tokens);

		List<MultiSignAddress> multiSignAddresses = new ArrayList<>();
		tokenInfo.setMultiSignAddresses(multiSignAddresses);

		for (PQKey ecKey : multiSigns) {
			multiSignAddresses.add(new MultiSignAddress(tokenid, "", ecKey.getPublicKeyAsHex()));
		}

		saveToken(tokenInfo, Coin.valueOf(1, tokenid), ownerKey, aesKey, ownerKey.getPubKey(),
				new MemoInfo("publishDomainName"));

	}

	public TokenIndexResponse getServerCalTokenIndex(String tokenid) throws Exception {
		HashMap<String, String> requestParam = new HashMap<>();
		requestParam.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		return Json.jsonmapper().readValue(resp, TokenIndexResponse.class);
	}

	public PermissionedAddressesResponse getPrevTokenMultiSignAddressList(Token token) throws Exception {
		HashMap<String, String> requestParam = new HashMap<>();
		requestParam.put("domainNameBlockHash", token.getDomainNameBlockHash());
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenPermissionedAddresses.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		return Json.jsonmapper().readValue(resp, PermissionedAddressesResponse.class);
	}

	public GetDomainTokenResponse getDomainNameBlockHash(String domainname) throws Exception {
		return getDomainNameBlockHash(domainname, "");
	}

	public GetDomainTokenResponse getDomainNameBlockHash(String domainname, String token) throws Exception {
		HashMap<String, String> requestParam = new HashMap<>();
		requestParam.put("domainname", domainname);
		requestParam.put("token", token);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getDomainNameBlockHash.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		return Json.jsonmapper().readValue(resp, GetDomainTokenResponse.class);
	}

	public Block multiSign(final String tokenid, PQKey outKey, KeyParameter aesKey) throws Exception {
		HashMap<String, Object> requestParam = new HashMap<>();

		String address = outKey.toAddress(params).toBase58();
		requestParam.put("address", address);
		requestParam.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenSignByAddress.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);
		if (multiSignResponse.getMultiSigns() == null || multiSignResponse.getMultiSigns().isEmpty())
			return null;
		MultiSign multiSign = multiSignResponse.getMultiSigns().get(0);

		byte[] payloadBytes = Utils.HEX.decode(multiSign.getBlockhashHex());
		Block block = params.getDefaultSerializer().makeBlock(payloadBytes);
		// replace block prototype if it is too too old

		Transaction transaction = block.getTransactions().get(0);

		List<MultiSignBy> multiSignBies;
		if (transaction.getDataSignature() == null) {
			multiSignBies = new ArrayList<>();
		} else {
			MultiSignByRequest multiSignByRequest = Json.jsonmapper().readValue(transaction.getDataSignature(),
					MultiSignByRequest.class);
			multiSignBies = multiSignByRequest.getMultiSignBies();
		}
		Sha256Hash sighash = transaction.getHash();
		SignatureBundle party1Signature = outKey.sign(sighash, aesKey);
		byte[] buf1 = party1Signature.encodeToDER();

		MultiSignBy multiSignBy0 = new MultiSignBy();

		multiSignBy0.setTokenid(multiSign.getTokenid());
		multiSignBy0.setTokenindex(multiSign.getTokenindex());
		multiSignBy0.setAddress(outKey.toAddress(params).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

		return adjustSolveAndSign(checkBlockPrototype(block));
	}

	private Block checkBlockPrototype(Block oldBlock) throws IOException {

		int time = 60 * 60 * 8;
		if (System.currentTimeMillis() / 1000 - oldBlock.getTimeSeconds() > time) {
			Block block = fetchTipBlock();
			block.setBlockType(oldBlock.getBlockType());
			for (Transaction transaction : oldBlock.getTransactions()) {
				block.addTransaction(transaction);
			}
			return block;
		} else {
			return oldBlock;
		}
	}

	public Long calc(long m, long factor, long d) {
		return BigInteger.valueOf(m).multiply(BigInteger.valueOf(factor)).divide(BigInteger.valueOf(d)).longValue();
	}


	public Block createToken(PQKey key, String domainname, boolean increment, Token token,
			List<MultiSignAddress> addresses, byte[] pubkeyTo, MemoInfo memoInfo) throws Exception {
		Token domain = getDomainNameBlockHash(domainname, "token").getdomainNameToken();
		token.setDomainName(domain.getTokenname());
		token.setDomainNameBlockHash(domain.getBlockHashHex());

		String tokenid = token.getTokenid();
		// key.getPublicKeyAsHex();

		HashMap<String, String> requestParam00 = new HashMap<>();
		requestParam00.put("tokenid", tokenid);
		byte[] resp2 = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam00));
		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);

		token.setTokenindex(tokenIndexResponse.getTokenindex());
		token.setPrevblockhash(tokenIndexResponse.getBlockhash());
		token.setTokenstop(!increment);
		TokenInfo tokenInfo = new TokenInfo();
		// tokens.setTokentype(TokenBlockType.currency.ordinal());
		tokenInfo.setToken(token);
		tokenInfo.setMultiSignAddresses(addresses);
        return saveToken(tokenInfo, new Coin(token.getAmount(), tokenid), key, null, pubkeyTo, memoInfo);
	}

 
	public Block getBlock(String hashHex) throws IOException {

		Map<String, Object> requestParam = new HashMap<>();
		requestParam.put("hashHex", hashHex);

		byte[] data = OkHttp3Util.postAndGetBlock(getServerURL() + ReqCmd.getBlockByHash.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		return params.getDefaultSerializer().makeBlock(data);
	}

	public BigDecimal getLastPrice(String tokenid, String basetoken) throws IOException, NoDataException {
		List<String> tokenids = new ArrayList<>();
		tokenids.add(tokenid);
		HashMap<String, Object> requestParam = new HashMap<>();
		requestParam.put("tokenids", tokenids);
		requestParam.put("count", 1);
		requestParam.put("basetoken", basetoken);
		byte[] response0 = OkHttp3Util.post(getServerURL() + ReqCmd.getOrdersTicker.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());
		OrderTickerResponse orderTickerResponse = Json.jsonmapper().readValue(response0, OrderTickerResponse.class);
		if (orderTickerResponse != null && !orderTickerResponse.getTickers().isEmpty()) {
			MatchLastdayResult matchResult = orderTickerResponse.getTickers().get(0);
			Token base = orderTickerResponse.getTokennames().get(matchResult.getBasetokenid());
			Integer priceshift = params.getOrderPriceShift(matchResult.getBasetokenid());
			// price is in orderbasetoken
			String price = MonetaryFormat.FIAT.noCode().format(matchResult.getPrice(), base.getDecimals() + priceshift);
			return new BigDecimal(price);
		}
		throw new NoDataException("tokenid=" + tokenid + " basetoken=" + basetoken);
	}

}
