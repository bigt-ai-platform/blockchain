package net.bigtangle.server.service.base;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import jakarta.annotation.Nullable;

import net.bigtangle.server.service.base.handler.SolidityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventInfo;
import net.bigtangle.core.EVMTransactionInfo;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.OrderCancelInfo;
import net.bigtangle.core.OrderOpenInfo;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.exception.VerificationException.ConflictPossibleException;
import net.bigtangle.exception.VerificationException.GenesisBlockDisallowedException;
import net.bigtangle.exception.VerificationException.IncorrectTransactionCountException;
import net.bigtangle.exception.VerificationException.InsufficientSignaturesException;
import net.bigtangle.exception.VerificationException.InvalidDependencyException;
import net.bigtangle.exception.VerificationException.InvalidOrderException;
import net.bigtangle.exception.VerificationException.InvalidSignatureException;
import net.bigtangle.exception.VerificationException.InvalidTokenOutputException;
import net.bigtangle.exception.VerificationException.InvalidTransactionDataException;
import net.bigtangle.exception.VerificationException.InvalidTransactionException;
import net.bigtangle.exception.VerificationException.MalformedTransactionDataException;
import net.bigtangle.exception.VerificationException.MissingDependencyException;
import net.bigtangle.exception.VerificationException.MissingSignatureException;
import net.bigtangle.exception.VerificationException.MissingTransactionDataException;
import net.bigtangle.exception.VerificationException.NotCoinbaseException;
import net.bigtangle.exception.VerificationException.PreviousTokenDisallowsException;
import net.bigtangle.exception.VerificationException.SigOpsException;
import net.bigtangle.exception.VerificationException.TimeReversionException;
import net.bigtangle.exception.VerificationException.TransactionOutputsDisallowedException;
import net.bigtangle.exception.VerificationException.UnsolidException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.GetTXRewardListResponse;
import net.bigtangle.response.GetTXRewardResponse;
import net.bigtangle.response.MultiSignByRequest;
import net.bigtangle.script.Script;
import net.bigtangle.script.Script.VerifyFlag;
import net.bigtangle.server.config.BurnedAddress;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.data.SolidityState.State;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.ContextPropagatingThreadFactory;
import net.bigtangle.utils.DomainValidator;

public class ServiceBaseCheck extends ServiceBaseConnect {

	public ServiceBaseCheck(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService, ObjectMapper jsonmapper) {
		super(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);

	}

	private static final Logger logger = LoggerFactory.getLogger(ServiceBaseCheck.class);

	private void checCoinbaseTransactionalSolidity(Block block, BlockStoreInterface store) throws BlockStoreException {
		// only reward block and contract can be set coinbase and check by caculation
		for (final Transaction tx : block.getTransactions()) {
			if (tx.isCoinBase() && (block.getBlockType() == BlockType.BLOCKTYPE_BEACON)) {
				throw new InvalidTransactionException("coinbase is not allowed ");
			}
		}

	}

	private SolidityState checkFullTransactionalSolidity(Block block, long height, boolean throwExceptions,
			BlockStoreInterface store) throws BlockStoreException {
		return checkFullTransactionalSolidity(block, height, throwExceptions, store, false);
	}

	private SolidityState checkFullTransactionalSolidity(Block block, long height, boolean throwExceptions,
			BlockStoreInterface store, boolean batch) throws BlockStoreException {

		// Batch blocks from mempool: transactions are already validated,
		// skip the expensive solidity check entirely.
		if (batch) {
			return SolidityState.getSuccessState();
		}

		checCoinbaseTransactionalSolidity(block, store);

		List<Transaction> transactions = block.getTransactions();

		// Cache: reuse UTXOs fetched in the first pass to avoid re-querying
		// the DB in the second pass. Keys are "blockHash:txHash:index".
		Map<String, UTXO> utxoCache = new HashMap<>();
		List<TransactionOutPoint> allInputTx = new ArrayList<>();
		for (final Transaction tx : transactions) {
			if (!tx.isCoinBase()) {
				for (int index = 0; index < tx.getInputs().size(); index++) {
					TransactionInput in = tx.getInputs().get(index);
					String cacheKey = in.getOutpoint().getBlockHash() + ":"
							+ in.getOutpoint().getTxHash() + ":" + in.getOutpoint().getIndex();
					UTXO prevOut = store.getTransactionOutput(in.getOutpoint().getBlockHash(),
							in.getOutpoint().getTxHash(), in.getOutpoint().getIndex());
					if (prevOut == null) {
						return SolidityState.from(in.getOutpoint(), true);
					}
					utxoCache.put(cacheKey, prevOut);
					if (checkUnique(allInputTx, in.getOutpoint())) {
						throw new InvalidTransactionException(
								"input outputpoint is not unique " + in.getOutpoint().toString());
					}
					allInputTx.add(in.getOutpoint());
				}
				if (checkBurnedFromAddress(tx, block.getLastMiningRewardBlock())) {
					throw new InvalidTransactionException("Burned Address");
				}
			}

		}

		// Transaction validation
		try {
			LinkedList<UTXO> txOutsSpent = new LinkedList<UTXO>();
			long sigOps = 0;

			if (scriptVerificationExecutor.isShutdown())
				scriptVerificationExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

			List<Future<VerificationException>> listScriptVerificationResults = new ArrayList<Future<VerificationException>>(
					block.getTransactions().size());

			for (Transaction tx : block.getTransactions()) {
				sigOps += tx.getSigOpCount();
			}
			// pro block check fee
			boolean checkFee = false;
			if (block.getBlockType().equals(BlockType.BLOCKTYPE_BEACON)) {
				checkFee = true;
			}
			for (final Transaction tx : block.getTransactions()) {
				boolean isCoinBase = tx.isCoinBase();
				Map<String, Coin> valueIn = new HashMap<String, Coin>();
				Map<String, Coin> valueOut = new HashMap<String, Coin>();

				final List<Script> prevOutScripts = new LinkedList<Script>();
				final Set<VerifyFlag> verifyFlags = networkParameters.getTransactionVerificationFlags();
				if (!isCoinBase) {
					for (int index = 0; index < tx.getInputs().size(); index++) {
						TransactionInput in = tx.getInputs().get(index);
						String cacheKey = in.getOutpoint().getBlockHash() + ":"
								+ in.getOutpoint().getTxHash() + ":" + in.getOutpoint().getIndex();
						UTXO prevOut = utxoCache.get(cacheKey);
						if (prevOut == null) {
							throw new RuntimeException("Block attempts to spend a not yet existent output: "
									+ in.getOutpoint().toString());
						}

						if (valueIn.containsKey(Utils.HEX.encode(prevOut.getValue().getTokenid()))) {
							valueIn.put(Utils.HEX.encode(prevOut.getValue().getTokenid()), valueIn
									.get(Utils.HEX.encode(prevOut.getValue().getTokenid())).add(prevOut.getValue()));
						} else {
							valueIn.put(Utils.HEX.encode(prevOut.getValue().getTokenid()), prevOut.getValue());

						}
						if (verifyFlags.contains(VerifyFlag.P2SH)) {
							if (prevOut.getScript().isPayToScriptHash())
								sigOps += Script.getP2SHSigOpCount(in.getScriptBytes());
							if (sigOps > NetworkParameters.MAX_BLOCK_SIGOPS)
								throw new SigOpsException();
						}
						prevOutScripts.add(prevOut.getScript());
						txOutsSpent.add(prevOut);
					}
				}
				// Sha256Hash hash = tx.getHash();
				for (TransactionOutput out : tx.getOutputs()) {
					if (valueOut.containsKey(Utils.HEX.encode(out.getValue().getTokenid()))) {
						valueOut.put(Utils.HEX.encode(out.getValue().getTokenid()),
								valueOut.get(Utils.HEX.encode(out.getValue().getTokenid())).add(out.getValue()));
					} else {
						valueOut.put(Utils.HEX.encode(out.getValue().getTokenid()), out.getValue());
					}
				}
				if (!checkTxOutputSigns(valueOut))
					throw new InvalidTransactionException("Transaction output value negative");
				if (isCoinBase) {
					// coinbaseValue = valueOut;
				} else {
					if (checkTxInputOutput(valueIn, valueOut, block)) {
						checkFee = true;
					}
				}

				if (!isCoinBase) {
					FutureTask<VerificationException> future = new FutureTask<VerificationException>(
							new Verifier(tx, prevOutScripts, verifyFlags));
					scriptVerificationExecutor.execute(future);
					listScriptVerificationResults.add(future);
				}
			}
			if (!checkFee)
				throw new VerificationException.NoFeeException(Coin.FEE_DEFAULT.toString());

			for (Future<VerificationException> future : listScriptVerificationResults) {
					VerificationException e;
					try {
						e = future.get();
					} catch (InterruptedException thrownE) {
						throw new RuntimeException(thrownE);
					} catch (ExecutionException thrownE) {
						throw new VerificationException(
								"Bug in Script.correctlySpends, likely script malformed in some new and interesting way.",
								thrownE);
					}
					if (e != null)
						throw e;
				}
		} catch (VerificationException e) {
			if (throwExceptions) {
				logger.info("", e);
				throw e;
			}
			logger.trace("", e);
			return SolidityState.getFailState();
		} finally {
			scriptVerificationExecutor.shutdownNow();
		}

		return SolidityState.getSuccessState();
	}

	private Boolean checkBurnedFromAddress(final Transaction tx, Long chain) {
		String fromAddress = fromAddress(tx);
		for (BurnedAddress burned : BurnedAddress.init()) {
			// logger.debug(" checkBurnedFromAddress " + fromAddress + " " +
			// burned.getLockaddress() + " " + chain + " "
			// + burned.getChain());
			if (burned.getLockaddress().equals(fromAddress) && chain >= burned.getChain()) {
				return true;
			}
		}

		return false;

	}

	private String fromAddress(final Transaction tx) {
		String fromAddress = "";
		for (TransactionInput t : tx.getInputs()) {
			try {
				if (t.getConnectedOutput().getScriptPubKey().isSentToAddress()) {
					fromAddress = t.getFromAddress().toBase58();
				} else {
					fromAddress =   Address.fromHash160(networkParameters,
							Utils.sha256hash160(t.getConnectedOutput().getScriptPubKey().getPubKey())).toBase58();

				}

				if (!fromAddress.equals(""))
					return fromAddress;
			} catch (Exception e) {
				return "";
			}
		}
		return fromAddress;

	}

	private boolean checkTxOutputSigns(Map<String, Coin> valueOut) {
		for (Map.Entry<String, Coin> entry : valueOut.entrySet()) {
			// System.out.println(entry.getKey() + "/" + entry.getValue());
			if (entry.getValue().signum() < 0) {
				return false;
			}
		}
		return true;
	}

	private boolean checkTxInputOutput(Map<String, Coin> valueInput, Map<String, Coin> valueOut, Block block) {
		boolean checkFee = false;

		for (Map.Entry<String, Coin> entry : valueOut.entrySet()) {
			if (!valueInput.containsKey(entry.getKey())) {
				throw new InvalidTransactionException("Transaction input and output values do not match");
			} else {
				// add check fee
				if (entry.getValue().isBIG() && !checkFee) {
					if (valueInput.get(entry.getKey()).compareTo(entry.getValue().add(Coin.FEE_DEFAULT)) >= 0) {
						checkFee = true;
					}
				}
				if (valueInput.get(entry.getKey()).compareTo(entry.getValue()) < 0) {
					throw new InvalidTransactionException("Transaction input and output values do not match");

				}
			}
		}
		// add check fee, no big in valueOut, but valueInput contain fee
		if (!checkFee) {
			if (valueOut.get(NetworkParameters.BIGTANGLE_TOKENID_STRING) == null) {
				if (valueInput.get(NetworkParameters.BIGTANGLE_TOKENID_STRING) != null && valueInput
						.get(NetworkParameters.BIGTANGLE_TOKENID_STRING).compareTo(Coin.FEE_DEFAULT) >= 0) {
					checkFee = true;
				}
			}
		}
		return checkFee;
	}

	private SolidityState checkFullTypeSpecificSolidity(Block block, BlockWrap storedPrev, BlockWrap storedPrevBranch,
			long height, boolean throwExceptions, BlockStoreInterface store) throws BlockStoreException {
		// Layer strategy: if a handler is registered for this block type,
		// delegate the full solidity check to it. Otherwise fall through to the
		// in-class switch below (existing behaviour). See LAYERING-PLAN.md.
		if (handlerFor(block.getBlockType()).isPresent()) {
			SolidityContext ctx = SolidityContext.builder().block(block).store(store).height(height)
					.throwExceptions(throwExceptions).base(this).build();
			return handlerFor(block.getBlockType()).get().checkFull(ctx);
		}
		switch (block.getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE:
			break;
		case BLOCKTYPE_FILE:
			break;
		case BLOCKTYPE_GOVERNANCE:
			break;
		case BLOCKTYPE_INITIAL:
			break;
		case BLOCKTYPE_BEACON:
			// Check rewards are solid
			SolidityState rewardSolidityState = checkFullRewardSolidity(block, storedPrev, storedPrevBranch, height,
					throwExceptions, store);
			if (!(rewardSolidityState.getState() == State.Success)) {
				return rewardSolidityState;
			}

			break;
		case BLOCKTYPE_TOKEN_CREATION:
			// Check token issuances are solid
			SolidityState tokenSolidityState = checkFullTokenSolidity(block, height, throwExceptions, store);
			if (!(tokenSolidityState.getState() == State.Success)) {
				return tokenSolidityState;
			}

			break;
		case BLOCKTYPE_TRANSFER:
			break;
		case BLOCKTYPE_USERDATA:
			break;
		case BLOCKTYPE_ORDER_OPEN:
			SolidityState openSolidityState = checkFullOrderOpenSolidity(block, height, throwExceptions, store);
			if (!(openSolidityState.getState() == State.Success)) {
				return openSolidityState;
			}
			break;
		case BLOCKTYPE_ORDER_CANCEL:
			SolidityState opSolidityState = checkFullOrderOpSolidity(block, height, throwExceptions, store);
			if (!(opSolidityState.getState() == State.Success)) {
				return opSolidityState;
			}
			break;
		case BLOCKTYPE_CONTRACT_EVENT:
			SolidityState check = checkFullContractEventSolidity(block, height, throwExceptions, store);
			if (!(check.getState() == State.Success)) {
				return check;
			}
			break;
		case BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;
		case BLOCKTYPE_EVM_DEPLOY:
		case BLOCKTYPE_EVM_CALL: {
			SolidityState evmState = checkFullEVMTransactionSolidity(block, throwExceptions, store);
			if (!(evmState.getState() == State.Success)) {
				return evmState;
			}
			break;
		}
		case BLOCKTYPE_STAKE:
			break;
		default:
			throw new RuntimeException("No Implementation");
		}

		return SolidityState.getSuccessState();
	}

	private SolidityState checkFullContractEventSolidity(Block block, long height, boolean throwExceptions,
			BlockStoreInterface store) throws BlockStoreException {
		return checkFormalContractEventSolidity(block, throwExceptions, store);
	}

	public SolidityState checkFullEVMTransactionSolidity(Block block, boolean throwExceptions,
			BlockStoreInterface store) throws BlockStoreException {
		List<Transaction> transactions = block.getTransactions();

		if (transactions.get(0).getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		EVMTransactionInfo info;
		try {
			info = new EVMTransactionInfo().parse(transactions.get(0).getData());
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		if (!transactions.get(0).getDataClassName().equals("EVMTransactionInfo")) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		if (info.getContractTokenid() == null || info.getFromAddress() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid EVM contract tokenid or sender");
			return SolidityState.getFailState();
		}

		Token contract = store.getTokenID(info.getContractTokenid()).get(0);
		String classname = new Utils().findContractValue(contract.getTokenKeyValues(), "classname");
		if (!"net.bigtangle.l1.evm.EVMContract".equals(classname)) {
			if (throwExceptions)
				throw new VerificationException("Not an EVM contract");
			return SolidityState.getFailState();
		}

		if (info.getValue() == null || info.getGasPrice() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("EVM value and gasPrice are required");
			return SolidityState.getFailState();
		}
		if (info.getGasLimit() <= 0) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid EVM gas limit");
			return SolidityState.getFailState();
		}
		if (info.getValue().signum() < 0) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid EVM value");
			return SolidityState.getFailState();
		}

		// The deposited amount must match the burned UTXOs of the block.
		String tokenid = info.getTokenid() != null ? info.getTokenid() : contract.getTokenid();
		Coin burned = countBurnedToken(block, store, tokenid);
		if (burned == null || !burned.getValue().equals(info.getValue())) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("EVM deposit value does not match burned UTXOs");
			return SolidityState.getFailState();
		}

		if (info.isCall() && info.getTo() != null) {
			String to = info.getTo().startsWith("0x") || info.getTo().startsWith("0X") ? info.getTo().substring(2)
					: info.getTo();
			if (to.length() != 40 || !to.matches("[0-9a-fA-F]+")) {
				if (throwExceptions)
					throw new InvalidTransactionDataException("Invalid EVM recipient address");
				return SolidityState.getFailState();
			}
		}

		return SolidityState.getSuccessState();
	}

	public SolidityState checkFullOrderOpenSolidity(Block block, long height, boolean throwExceptions,
			BlockStoreInterface store) throws BlockStoreException {
		List<Transaction> transactions = block.getTransactions();

		if (transactions.get(0).getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		// Check that the tx has correct data
		OrderOpenInfo orderInfo;
		try {
			orderInfo = new OrderOpenInfo().parse(transactions.get(0).getData());
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		if (!transactions.get(0).getDataClassName().equals("OrderOpen")) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		// NotNull checks
		if (orderInfo.getTargetTokenid() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid target tokenid");
			return SolidityState.getFailState();
		}

		// Check bounds for target coin values
		if (orderInfo.getTargetValue() < 1 || orderInfo.getTargetValue() > Long.MAX_VALUE) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid target value");
			return SolidityState.getFailState();
		}

		// Check that the tx inputs only burn one type of tokens, only for offerTokenid
		Coin burnedCoins = null;
		if (orderInfo.getVersion() > 1) {
			burnedCoins = countBurnedToken(block, store, orderInfo.getOfferTokenid());
		} else {
			burnedCoins = countBurnedToken(block, store);
		}

		if (burnedCoins == null || burnedCoins.getValue().longValue() == 0) {
			if (throwExceptions)
				// throw new InvalidOrderException("No tokens were offered.");
				return SolidityState.getFailState();
		}

		if (burnedCoins.getValue().longValue() > Long.MAX_VALUE) {
			if (throwExceptions)
				throw new InvalidOrderException("The order is too large.");
			return SolidityState.getFailState();
		}
		// calculate the offervalue for version == 1
		if (orderInfo.getVersion() == 1) {
			orderInfo.setOfferValue(burnedCoins.getValue().longValue());
			orderInfo.setOfferTokenid(burnedCoins.getTokenHex());
		}

		// Check that the tx inputs only burn must be the offerValue
		if (burnedCoins.isBIG()) {
			// fee
			if (!burnedCoins.subtract(Coin.FEE_DEFAULT)
					.equals(new Coin(orderInfo.getOfferValue(), Utils.HEX.decode(orderInfo.getOfferTokenid())))) {
				if (throwExceptions)
					throw new InvalidOrderException("The Transaction data burnedCoins is not same as OfferValue .");
				return SolidityState.getFailState();

			}
		} else {
			if (!burnedCoins
					.equals(new Coin(orderInfo.getOfferValue(), Utils.HEX.decode(orderInfo.getOfferTokenid())))) {
				if (throwExceptions)
					throw new InvalidOrderException("The Transaction data burnedCoins is not same as OfferValue .");
				return SolidityState.getFailState();
			}
		}
		// Check that either the burnt token or the target token base token
		if (checkOrderBaseToken(orderInfo, burnedCoins)) {
			if (throwExceptions)
				throw new InvalidOrderException(
						"Invalid exchange combination. Ensure order base token is sold or bought.");
			return SolidityState.getFailState();
		}

		// Check that we have a correct price given in full Base Token
		if (orderInfo.getPrice() != null && orderInfo.getPrice() <= 0 && orderInfo.getVersion() > 1) {
			if (throwExceptions)
				throw new InvalidOrderException("The given order's price is not integer.");
			return SolidityState.getFailState();
		}

		if (orderInfo.getValidToTime() > Math.addExact(orderInfo.getValidFromTime(),
				NetworkParameters.ORDER_TIMEOUT_MAX)) {
			if (throwExceptions)
				throw new InvalidOrderException("The given order's timeout is too long.");
			return SolidityState.getFailState();
		}

		if (!PQKey.fromPublicOnly(orderInfo.getBeneficiaryPubKey()).toAddress(networkParameters).toBase58()
				.equals(orderInfo.getBeneficiaryAddress())) {
			if (throwExceptions)
				throw new InvalidOrderException("The address does not match with the given pubkey.");
			return SolidityState.getFailState();
		}

		return SolidityState.getSuccessState();
	}

	private boolean checkOrderBaseToken(OrderOpenInfo orderInfo, Coin burnedCoins) {
		return burnedCoins.getTokenHex().equals(orderInfo.getOrderBaseToken())
				&& orderInfo.getTargetTokenid().equals(orderInfo.getOrderBaseToken())
				|| !burnedCoins.getTokenHex().equals(orderInfo.getOrderBaseToken())
						&& !orderInfo.getTargetTokenid().equals(orderInfo.getOrderBaseToken());
	}

	public Coin countBurnedToken(Block block, BlockStoreInterface store) throws BlockStoreException {
		Coin burnedCoins = null;
		for (final Transaction tx : block.getTransactions()) {
			for (int index = 0; index < tx.getInputs().size(); index++) {
				TransactionInput in = tx.getInputs().get(index);
				UTXO prevOut = store.getTransactionOutput(in.getOutpoint().getBlockHash(), in.getOutpoint().getTxHash(),
						in.getOutpoint().getIndex());
				if (prevOut == null) {
					// Cannot happen due to solidity checks before
					throw new RuntimeException("Block attempts to spend a not yet existent output block: "
							+ getBlock(in.getOutpoint().getBlockHash(), store).toString()
							+ " \n countBurnedToken block = " + block.toString());
				}

				if (burnedCoins == null)
					burnedCoins = Coin.valueOf(0, Utils.HEX.encode(prevOut.getValue().getTokenid()));

				try {
					burnedCoins = burnedCoins.add(prevOut.getValue());
				} catch (IllegalArgumentException e) {
					throw new InvalidOrderException(e.getMessage());
				}
			}

			for (int index = 0; index < tx.getOutputs().size(); index++) {
				TransactionOutput out = tx.getOutputs().get(index);

				try {
					burnedCoins = burnedCoins.subtract(out.getValue());
				} catch (IllegalArgumentException e) {
					throw new InvalidOrderException(e.getMessage());
				}
			}
		}
		return burnedCoins;
	}

	/**
	 * Counts the number tokens that are being burned in this block. If multiple
	 * tokens exist in the transaction, throws InvalidOrderException.
	 * 
	 * @param block
	 * @return
	 * @throws BlockStoreException
	 */
	public Coin countBurnedToken(Block block, BlockStoreInterface store, String tokenid) throws BlockStoreException {
		Coin burnedCoins = null;
		for (final Transaction tx : block.getTransactions()) {

			for (int index = 0; index < tx.getInputs().size(); index++) {
				TransactionInput in = tx.getInputs().get(index);
				UTXO prevOut = store.getTransactionOutput(in.getOutpoint().getBlockHash(), in.getOutpoint().getTxHash(),
						in.getOutpoint().getIndex());
				if (prevOut == null) {
					// Cannot happen due to solidity checks before
					throw new RuntimeException(
							"Block attempts to spend a not yet existent output: " + in.getOutpoint().toString());

				}
				if (Utils.HEX.encode(prevOut.getValue().getTokenid()).equals(tokenid)) {
					if (burnedCoins == null)
						burnedCoins = Coin.valueOf(0, Utils.HEX.encode(prevOut.getValue().getTokenid()));

					try {
						burnedCoins = burnedCoins.add(prevOut.getValue());
					} catch (IllegalArgumentException e) {
						throw new InvalidOrderException(e.getMessage());
					}
				}
			}

			for (int index = 0; index < tx.getOutputs().size(); index++) {
				TransactionOutput out = tx.getOutputs().get(index);

				try {
					if (Utils.HEX.encode(out.getValue().getTokenid()).equals(tokenid)) {
						burnedCoins = burnedCoins.subtract(out.getValue());
					}
				} catch (IllegalArgumentException e) {
					throw new InvalidOrderException(e.getMessage());
				}
			}
		}
		return burnedCoins;
	}

	public SolidityState checkFullOrderOpSolidity(Block block, long height, boolean throwExceptions,
			BlockStoreInterface store) throws BlockStoreException {

		// No output creation
		if (!block.getTransactions().get(0).getOutputs().isEmpty()) {
			if (throwExceptions)
				throw new TransactionOutputsDisallowedException();
			return SolidityState.getFailState();
		}

		Transaction tx = block.getTransactions().get(0);
		if (tx.getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		OrderCancelInfo info = null;
		try {
			info = new OrderCancelInfo().parse(tx.getData());
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		// NotNull checks
		if (info.getBlockHash() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid target txhash");
			return SolidityState.getFailState();
		}

		// Ensure the predecessing order exists
		OrderRecord order = store.getOrder(info.getBlockHash(), Sha256Hash.ZERO_HASH);
		if (order == null) {
			return SolidityState.from(info.getBlockHash(), true);
		}

		byte[] pubKey = order.getBeneficiaryPubKey();
		byte[] data = tx.getHash().getBytes();
		byte[] signature = block.getTransactions().get(0).getDataSignature();

		// If signature of beneficiary is missing, fail
		if (!PQScriptUtils.verifyPQ(pubKey, signature, Sha256Hash.wrap(data))) {
			if (throwExceptions)
				throw new InsufficientSignaturesException();
			return SolidityState.getFailState();
		}

		return SolidityState.getSuccessState();
	}

	public SolidityState checkFullRewardSolidity(Block block, BlockWrap storedPrev, BlockWrap storedPrevBranch,
			long height, boolean throwExceptions, BlockStoreInterface store) throws BlockStoreException {
		List<Transaction> transactions = block.getTransactions();

		if (transactions.size() != 1) {
			if (throwExceptions)
				throw new IncorrectTransactionCountException();
			return SolidityState.getFailState();
		}

		// No output creation
		if (!transactions.get(0).getOutputs().isEmpty()) {
			if (throwExceptions)
				throw new TransactionOutputsDisallowedException();
			return SolidityState.getFailState();
		}

		if (transactions.get(0).getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		// Check that the tx has correct data
		RewardInfo rewardInfo = new RewardInfo().parseChecked(transactions.get(0).getData());

		// NotNull checks
		if (rewardInfo.getPrevRewardHash() == null) {
			if (throwExceptions)
				throw new MissingDependencyException();
			return SolidityState.getFailState();
		}

		// Ensure dependency (prev reward hash) exists
		Sha256Hash prevRewardHash = rewardInfo.getPrevRewardHash();
		BlockWrap dependency = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,
				jsonmapper).getBlockWrap(prevRewardHash, store);
		if (dependency == null)
			return SolidityState.fromPrevReward(prevRewardHash, true);

		// Ensure dependency (prev reward hash) is valid predecessor
		if (dependency.getBlock().getBlockType() != BlockType.BLOCKTYPE_INITIAL
				&& dependency.getBlock().getBlockType() != BlockType.BLOCKTYPE_BEACON) {
			if (throwExceptions)
				throw new InvalidDependencyException("Predecessor is not reward or genesis");
			return SolidityState.getFailState();
		}

		return SolidityState.getSuccessState();
	}

	public SolidityState checkFullTokenSolidity(Block block, long height, boolean throwExceptions,
			BlockStoreInterface store) throws BlockStoreException {

		// TODO (check fee get(1))
		if (!block.getTransactions().get(0).isCoinBase()) {
			if (throwExceptions)
				throw new NotCoinbaseException();
			return SolidityState.getFailState();
		}

		Transaction tx = block.getTransactions().get(0);
		if (tx.getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		TokenInfo currentToken = null;
		try {
			currentToken = new TokenInfo().parse(tx.getData());
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		if (checkFormalTokenFields(throwExceptions, currentToken) == SolidityState.getFailState())
			return SolidityState.getFailState();

		// Check field correctness: amount
		if (!currentToken.getToken().getAmount().equals(block.getTransactions().get(0).getOutputSum())) {
		//	logger.debug("Incorrect amount field" + currentToken.getToken().getAmount() + " !="
		//			+ block.getTransactions().get(0).getOutputSum());
			if (throwExceptions){
				logger.debug(block.toString());
				 
				throw new InvalidTransactionDataException("Incorrect amount field" + currentToken.getToken().getAmount() + " !="
					+ block.getTransactions().get(0).getOutputSum());
				}
		}

		// Check all token issuance transaction outputs are actually of the
		// given token or fee
		for (Transaction tx1 : block.getTransactions()) {
			for (TransactionOutput out : tx1.getOutputs()) {
				if (!out.getValue().getTokenHex().equals(currentToken.getToken().getTokenid())
						&& !out.getValue().isBIG()) {
					if (throwExceptions)
						throw new InvalidTokenOutputException();
					return SolidityState.getFailState();
				}
			}
		}

		// Check previous issuance hash exists or initial issuance
		boolean initIssue=currentToken.getToken().getPrevblockhash() == null ||  	Sha256Hash.ZERO_HASH.equals(currentToken.getToken().getPrevblockhash());
		if (( initIssue
		&& currentToken.getToken().getTokenindex() != 0)
				|| (!initIssue
						&& currentToken.getToken().getTokenindex() == 0)) {
			if (throwExceptions)
				throw new MissingDependencyException(block.toString());
			return SolidityState.getFailState();
		}

		// Must define enough permissioned addresses
		if (currentToken.getToken().getSignnumber() > currentToken.getMultiSignAddresses().size()) {
			if (throwExceptions)
				throw new InvalidTransactionDataException(
						"Cannot fulfill required sign number from multisign address list");
			return SolidityState.getFailState();
		}

		// Must have a predecessing domain definition
		if (currentToken.getToken().getDomainNameBlockHash() == null) {
			if (throwExceptions)
				throw new InvalidDependencyException("Domain predecessor is empty");
			return SolidityState.getFailState();
		}

		// Requires the predecessing domain definition block to exist and be a
		// legal domain
		Token prevDomain = null;

		if (!currentToken.getToken().getDomainNameBlockHash()
				.equals(UtilGeneseBlock.createGenesis(networkParameters ).getHashAsString())) {

			prevDomain = store.getTokenByBlockHash(Sha256Hash.wrap(currentToken.getToken().getDomainNameBlockHash()));
			if (prevDomain == null) {
				if (throwExceptions)
					throw new MissingDependencyException();
				return SolidityState.from(Sha256Hash.wrap(currentToken.getToken().getDomainNameBlockHash()), true);
			}

		}
		// Ensure signatures exist
		int signatureCount = 0;
		if (tx.getDataSignature() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		// Get signatures from transaction
		String jsonStr = new String(tx.getDataSignature());
		MultiSignByRequest txSignatures;
		try {
			txSignatures = jsonmapper.readValue(jsonStr, MultiSignByRequest.class);
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		// Get permissioned addresses
		Token prevToken = null;
		List<MultiSignAddress> permissionedAddresses = new ArrayList<MultiSignAddress>();
		// If not initial issuance, we check according to the previous token
		if (currentToken.getToken().getTokenindex() != 0) {
			try {
				// Previous issuance must exist to check solidity
				prevToken = store.getTokenByBlockHash(currentToken.getToken().getPrevblockhash());
				if (prevToken == null) {
					return SolidityState.from(currentToken.getToken().getPrevblockhash(), true);
				}

				// Compare members of previous and current issuance
				if (!currentToken.getToken().getTokenid().equals(prevToken.getTokenid())) {
					if (throwExceptions)
						throw new InvalidDependencyException("Wrong token ID");
					return SolidityState.getFailState();
				}
				if (currentToken.getToken().getTokenindex() != prevToken.getTokenindex() + 1) {
					if (throwExceptions)
						throw new InvalidDependencyException("Wrong token index");
					return SolidityState.getFailState();
				}

				if (!currentToken.getToken().getTokenname().equals(prevToken.getTokenname())) {
					if (throwExceptions)
						throw new PreviousTokenDisallowsException("Cannot change token name");
					return SolidityState.getFailState();
				}

				if (currentToken.getToken().getDomainName() != null
						&& !currentToken.getToken().getDomainName().equals(prevToken.getDomainName())) {
					if (throwExceptions)
						throw new PreviousTokenDisallowsException("Cannot change token domain name");
					return SolidityState.getFailState();
				}

				if (currentToken.getToken().getDecimals() != prevToken.getDecimals()) {
					if (throwExceptions)
						throw new PreviousTokenDisallowsException("Cannot change token decimal");
					return SolidityState.getFailState();
				}
				if (currentToken.getToken().getTokentype() != prevToken.getTokentype()) {
					if (throwExceptions)
						throw new PreviousTokenDisallowsException("Cannot change token type");
					return SolidityState.getFailState();
				}
				if (!currentToken.getToken().getDomainNameBlockHash().equals(prevToken.getDomainNameBlockHash())) {
					if (throwExceptions)
						throw new PreviousTokenDisallowsException("Cannot change token domain");
					return SolidityState.getFailState();
				}

				// Must allow more issuances
				if (prevToken.isTokenstop()) {
					if (throwExceptions)
						throw new PreviousTokenDisallowsException("Previous token does not allow further issuance");
					return SolidityState.getFailState();
				}

				// Get addresses allowed to reissue
				permissionedAddresses.addAll(store.getMultiSignAddressListByTokenidAndBlockHashHex(
						prevToken.getTokenid(), prevToken.getBlockHash()));

			} catch (BlockStoreException e) {
				// Cannot happen, previous token must exist
				logger.error("Failed to get permissioned addresses for reissuance", e);
			}
		} else {
			// First time issuances must sign for the token id
			permissionedAddresses = currentToken.getMultiSignAddresses();

			// Any first time issuances also require the domain signatures
			List<MultiSignAddress> prevDomainPermissionedAddresses = queryDomainnameTokenMultiSignAddresses(
					prevDomain == null ? UtilGeneseBlock.createGenesis(networkParameters ).getHash() : prevDomain.getBlockHash(),
					store);
			SolidityState domainPermission = checkDomainPermission(prevDomainPermissionedAddresses,
					txSignatures.getMultiSignBies(), 1,
					// TODO remove the high level domain sign
					// only one sign of prev domain needed
					// prevDomain == null ? 1 : prevDomain.getSignnumber(),
					throwExceptions, tx.getHash());
			if (domainPermission != SolidityState.getSuccessState())
				return domainPermission;
		}

		// Get permissioned pubkeys wrapped to check for bytearray equality
		Set<ByteBuffer> permissionedPubKeys = new HashSet<ByteBuffer>();
		for (MultiSignAddress multiSignAddress : permissionedAddresses) {
			byte[] pubKey = Utils.HEX.decode(multiSignAddress.getPubKeyHex());
			permissionedPubKeys.add(ByteBuffer.wrap(pubKey));
		}

		// Ensure all multiSignBys pubkeys are from the permissioned list
		for (MultiSignBy multiSignBy : new ArrayList<>(txSignatures.getMultiSignBies())) {
			ByteBuffer pubKey = ByteBuffer.wrap(Utils.HEX.decode(multiSignBy.getPublickey()));
			if (!permissionedPubKeys.contains(pubKey)) {
				// If a pubkey is not from the list, drop it.
				txSignatures.getMultiSignBies().remove(multiSignBy);
				continue;
			} else {
				// Otherwise the listed address is used. Cannot use same address
				// multiple times.
				permissionedPubKeys.remove(pubKey);
			}
		}

		// For first issuance, ensure the tokenid pubkey signature exists to
		// prevent others from generating conflicts
		if (currentToken.getToken().getTokenindex() == 0) {
			if (permissionedPubKeys.contains(ByteBuffer.wrap(Utils.HEX.decode(currentToken.getToken().getTokenid())))) {
				if (throwExceptions)
					throw new MissingSignatureException();
				return SolidityState.getFailState();
			}
		}

		for (MultiSignBy multiSignBy : txSignatures.getMultiSignBies()) {
			byte[] pubKey = Utils.HEX.decode(multiSignBy.getPublickey());
			byte[] data = tx.getHash().getBytes();
			byte[] signature = Utils.HEX.decode(multiSignBy.getSignature());

			if (PQScriptUtils.verifyPQ(pubKey, signature, Sha256Hash.wrap(data))) {
				signatureCount++;
			} else {
				if (throwExceptions)
					throw new InvalidSignatureException();
				return SolidityState.getFailState();
			}
		}

		// Return whether sufficient signatures exist
		int requiredSignatureCount = prevToken != null ? prevToken.getSignnumber() : 1;
		if (signatureCount >= requiredSignatureCount)
			return SolidityState.getSuccessState();

		if (throwExceptions)
			throw new InsufficientSignaturesException();
		return SolidityState.getFailState();
	}

	private SolidityState checkDomainPermission(List<MultiSignAddress> permissionedAddresses,
			List<MultiSignBy> multiSignBies_0, int requiredSignatures, boolean throwExceptions, Sha256Hash txHash) {

		// Make original list inaccessible by cloning list
		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>(multiSignBies_0);

		// Get permissioned pubkeys wrapped to check for bytearray equality
		Set<ByteBuffer> permissionedPubKeys = new HashSet<ByteBuffer>();
		for (MultiSignAddress multiSignAddress : permissionedAddresses) {
			byte[] pubKey = Utils.HEX.decode(multiSignAddress.getPubKeyHex());
			permissionedPubKeys.add(ByteBuffer.wrap(pubKey));
		}

		// Ensure all multiSignBys pubkeys are from the permissioned list
		for (MultiSignBy multiSignBy : new ArrayList<MultiSignBy>(multiSignBies)) {
			ByteBuffer pubKey = ByteBuffer.wrap(Utils.HEX.decode(multiSignBy.getPublickey()));
			if (!permissionedPubKeys.contains(pubKey)) {
				// If a pubkey is not from the list, drop it.
				multiSignBies.remove(multiSignBy);
				continue;
			} else {
				// Otherwise the listed address is used. Cannot use same address
				// multiple times.
				permissionedPubKeys.remove(pubKey);
			}
		}

		// Verify signatures
		int signatureCount = 0;
		for (MultiSignBy multiSignBy : multiSignBies) {
			byte[] pubKey = Utils.HEX.decode(multiSignBy.getPublickey());
			byte[] data = txHash.getBytes();
			byte[] signature = Utils.HEX.decode(multiSignBy.getSignature());

			if (PQScriptUtils.verifyPQ(pubKey, signature, Sha256Hash.wrap(data))) {
				signatureCount++;
			} else {
				if (throwExceptions)
					throw new InvalidSignatureException();
				return SolidityState.getFailState();
			}
		}

		// Return whether sufficient signatures exist
		if (signatureCount >= requiredSignatures)
			return SolidityState.getSuccessState();
		else {
			if (throwExceptions)
				throw new InsufficientSignaturesException();
			return SolidityState.getFailState();
		}
	}

	private SolidityState checkFormalTokenFields(boolean throwExceptions, TokenInfo currentToken) {
		if (currentToken.getToken() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("getToken is null");
			return SolidityState.getFailState();
		}
		if (currentToken.getMultiSignAddresses() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("getMultiSignAddresses is null");
			return SolidityState.getFailState();
		}
		if (currentToken.getToken().getTokenid() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("getTokenid is null");
			return SolidityState.getFailState();
		}
		if (currentToken.getToken().getTokenid().equals(NetworkParameters.BIGTANGLE_TOKENID_STRING)) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Not allowed");
			return SolidityState.getFailState();
		}

		if (currentToken.getToken().getDescription() != null
				&& currentToken.getToken().getDescription().length() > Token.TOKEN_MAX_DESC_LENGTH) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Too long description");
			return SolidityState.getFailState();
		}

		if (currentToken.getToken().getTokenid() != null
				&& currentToken.getToken().getTokenid().length() > Token.TOKEN_MAX_ID_LENGTH) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Too long tokenid");
			return SolidityState.getFailState();
		}

		if (currentToken.getToken().getLanguage() != null
				&& currentToken.getToken().getLanguage().length() > Token.TOKEN_MAX_LANGUAGE_LENGTH) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Too long language");
			return SolidityState.getFailState();
		}
		if (currentToken.getToken().getClassification() != null
				&& currentToken.getToken().getClassification().length() > Token.TOKEN_MAX_CLASSIFICATION_LENGTH) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Too long classification");
			return SolidityState.getFailState();
		}
		if (currentToken.getToken().getTokenname() == null || "".equals(currentToken.getToken().getTokenname())) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Token name cannot be null.");
		}
		if (currentToken.getToken().getTokenname() != null
				&& currentToken.getToken().getTokenname().length() > Token.TOKEN_MAX_NAME_LENGTH) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Too long token name");
			return SolidityState.getFailState();
		}

		if (currentToken.getToken().getDomainName() != null
				&& currentToken.getToken().getDomainName().length() > Token.TOKEN_MAX_URL_LENGTH) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Too long domainname");
			return SolidityState.getFailState();
		}
		if (currentToken.getToken().getSignnumber() < 0) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid sign number");
			return SolidityState.getFailState();
		}

		return SolidityState.getSuccessState();
	}

	public SolidityState checkChainSolidity(Block block, boolean throwExceptions, BlockStoreInterface store)
			throws BlockStoreException {

		// Check the chain block formally valid
		checkFormalBlockSolidity(block, true);
		ServiceBaseConnect servicebase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		BlockWrap prevTrunkBlock = servicebase.getBlockWrap(block.getPrevBlockHash(), store);
		BlockWrap prevBranchBlock = servicebase.getBlockWrap(block.getPrevBranchBlockHash(), store);
		if (prevTrunkBlock == null)
			SolidityState.from(block.getPrevBlockHash(), true);
		if (prevBranchBlock == null)
			SolidityState.from(block.getPrevBranchBlockHash(), true);

		if (block.getLastMiningRewardBlock() != getRewardInfo(block).getChainlength()) {
			if (throwExceptions)
				throw new VerificationException("Reward chain length mismatch");
			return SolidityState.getFailState();
		}

		SolidityState difficultyResult = checkRewardChainLink(block, store);
		if (difficultyResult.notSuccessState()) {
			return difficultyResult;
		}

		SolidityState referenceResult = checkRewardReferencedBlocks(block, store);
		if (referenceResult.notSuccessState()) {
			return referenceResult;
		}

		// Solidify referenced blocks
		// solidifyBlocks(block.getRewardInfo(), store);

		return SolidityState.getSuccessState();
	}

	/**
	 * Checks if the block has all of its dependencies to fully determine its
	 * validity. Then checks if the block is valid based on its dependencies. If
	 * SolidityState.getSuccessState() is returned, the block is valid. If
	 * SolidityState.getFailState() is returned, the block is invalid. Otherwise,
	 * appropriate solidity states are returned to imply missing dependencies.
	 *
	 * @param block
	 * @return SolidityState
	 * @throws BlockStoreException
	 */
	public SolidityState checkSolidity(Block block, boolean throwExceptions, BlockStoreInterface store)
			throws BlockStoreException {
		return checkSolidity(block, throwExceptions, store, true);
	}

	public SolidityState checkSolidity(Block block, boolean throwExceptions, BlockStoreInterface store,
			boolean allowMissingPredecessor) throws BlockStoreException {
		return checkSolidity(block, throwExceptions, store, allowMissingPredecessor, false);
	}

	public SolidityState checkSolidity(Block block, boolean throwExceptions, BlockStoreInterface store,
			boolean allowMissingPredecessor, boolean batch) throws BlockStoreException {
		try {
			// Check formal correctness of the block
			SolidityState formalSolidityResult = checkFormalBlockSolidity(block, throwExceptions);
			if (formalSolidityResult.isFailState())
				return formalSolidityResult;
			final Set<Sha256Hash> allReuiredBlockHashes = getAllRequiredBlockHashes(block);
			List<BlockWrap> allRequirements = getAllBlocksFromHash(allReuiredBlockHashes, store);

			// Required must exist and be ok
			SolidityState check = checkRequiredAndOk(block, throwExceptions, allRequirements, store);
			if (check.notSuccessState()) {
				return check;
			}

			// Inherit solidity from predecessors if they are not solid
			SolidityState minPredecessorSolidity = getMinPredecessorSolidity(block, allRequirements, store,
					!allowMissingPredecessor);

			// For consensus blocks, it works as follows:
			// If solid == 1 or solid == 2, we also check for PoW now
			// since it is possible to do so
			if (block.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
				if (minPredecessorSolidity.getState() == State.MissingCalculation
						|| minPredecessorSolidity.getState() == State.Success) {
				}
			}

			// Inherit solidity from predecessors if they are not solid
			switch (minPredecessorSolidity.getState()) {
			case MissingCalculation:
			case Invalid:
			case MissingPredecessor:
				return minPredecessorSolidity;
			case Success:
				break;
			}

			// Otherwise, the solidity of the block itself is checked
			return checkFullBlockSolidity(block, throwExceptions, allRequirements, allowMissingPredecessor, store, batch);

		} catch (IllegalArgumentException e) {
			throw new VerificationException(e);
		}

	}

	private SolidityState checkFormalTransactionalSolidity(Block block, boolean throwExceptions)
			throws BlockStoreException {
		try {

			long sigOps = 0;

			for (Transaction tx : block.getTransactions()) {
				sigOps += tx.getSigOpCount();
			}

			for (final Transaction tx : block.getTransactions()) {
				Map<String, Coin> valueOut = new HashMap<String, Coin>();
				for (TransactionOutput out : tx.getOutputs()) {
					if (valueOut.containsKey(Utils.HEX.encode(out.getValue().getTokenid()))) {
						valueOut.put(Utils.HEX.encode(out.getValue().getTokenid()),
								valueOut.get(Utils.HEX.encode(out.getValue().getTokenid())).add(out.getValue()));
					} else {
						valueOut.put(Utils.HEX.encode(out.getValue().getTokenid()), out.getValue());
					}
				}
				if (!checkTxOutputSigns(valueOut)) {
					throw new InvalidTransactionException("Transaction output value negative");
				}

				final Set<VerifyFlag> verifyFlags = networkParameters.getTransactionVerificationFlags();
				if (verifyFlags.contains(VerifyFlag.P2SH)) {
					if (sigOps > NetworkParameters.MAX_BLOCK_SIGOPS)
						throw new SigOpsException();
				}
			}

		} catch (VerificationException e) {
			scriptVerificationExecutor.shutdownNow();
			if (throwExceptions) {
				logger.info("", e);
				throw e;
			}
			logger.trace("", e);
			return SolidityState.getFailState();
		}

		return SolidityState.getSuccessState();
	}

	private SolidityState checkFormalTypeSpecificSolidity(Block block, boolean throwExceptions)
			throws BlockStoreException {
		// Layer strategy: delegate to a registered handler if present.
		if (handlerFor(block.getBlockType()).isPresent()) {
			SolidityContext ctx = SolidityContext.builder().block(block)
					.throwExceptions(throwExceptions).base(this).build();
			return handlerFor(block.getBlockType()).get().checkFormal(ctx);
		}
		switch (block.getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE:
			break;
		case BLOCKTYPE_FILE:
			break;
		case BLOCKTYPE_GOVERNANCE:
			break;
		case BLOCKTYPE_INITIAL:
			break;
		case BLOCKTYPE_BEACON:
			// Check rewards are solid
			SolidityState rewardSolidityState = checkFormalRewardSolidity(block, throwExceptions);
			if (!(rewardSolidityState.getState() == State.Success)) {
				return rewardSolidityState;
			}

			break;
		case BLOCKTYPE_TOKEN_CREATION:
			// Check token issuances are solid
			SolidityState tokenSolidityState = checkFormalTokenSolidity(block, throwExceptions);
			if (!(tokenSolidityState.getState() == State.Success)) {
				return tokenSolidityState;
			}

			break;
		case BLOCKTYPE_TRANSFER:
			break;
		case BLOCKTYPE_USERDATA:
			break;
		case BLOCKTYPE_ORDER_OPEN:
			SolidityState openSolidityState = checkFormalOrderOpenSolidity(block, throwExceptions);
			if (!(openSolidityState.getState() == State.Success)) {
				return openSolidityState;
			}
			break;
		case BLOCKTYPE_ORDER_CANCEL:
			SolidityState opSolidityState = checkFormalOrderOpSolidity(block, throwExceptions);
			if (!(opSolidityState.getState() == State.Success)) {
				return opSolidityState;
			}
			break;
		case BLOCKTYPE_CONTRACT_EVENT:
			break;
		case BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;
		case BLOCKTYPE_EVM_DEPLOY:
		case BLOCKTYPE_EVM_CALL:
			break;
		case BLOCKTYPE_STAKE:
			break;
		default:
			throw new RuntimeException("No Implementation");
		}

		return SolidityState.getSuccessState();
	}

	public SolidityState checkFormalOrderOpenSolidity(Block block, boolean throwExceptions)
			throws BlockStoreException {
		List<Transaction> transactions = block.getTransactions();

		if (transactions.get(0).getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		// Check that the tx has correct data
		OrderOpenInfo orderInfo;
		try {
			orderInfo = new OrderOpenInfo().parse(transactions.get(0).getData());
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		if (!transactions.get(0).getDataClassName().equals("OrderOpen")) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		// NotNull checks
		if (orderInfo.getTargetTokenid() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid target tokenid");
			return SolidityState.getFailState();
		}

		// Check bounds for target coin values
		if (orderInfo.getTargetValue() < 1 || orderInfo.getTargetValue() > Long.MAX_VALUE) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid target long value: " + orderInfo.getTargetValue());
			return SolidityState.getFailState();
		}

		if (orderInfo.getValidToTime() > Math.addExact(orderInfo.getValidFromTime(),
				NetworkParameters.ORDER_TIMEOUT_MAX)) {
			if (throwExceptions)
				throw new InvalidOrderException("The given order's timeout is too long.");
			return SolidityState.getFailState();
		}

		if (!PQKey.fromPublicOnly(orderInfo.getBeneficiaryPubKey()).toAddress(networkParameters).toBase58()
				.equals(orderInfo.getBeneficiaryAddress())) {
			if (throwExceptions)
				throw new InvalidOrderException("The address does not match with the given pubkey.");
			return SolidityState.getFailState();
		}

		return SolidityState.getSuccessState();
	}

	public SolidityState checkFormalContractEventSolidity(Block block, boolean throwExceptions,
			BlockStoreInterface store) throws BlockStoreException {
		List<Transaction> transactions = block.getTransactions();

		if (transactions.get(0).getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		// Check that the tx has correct data
		ContractEventInfo contractEventInfo;
		try {
			contractEventInfo = new ContractEventInfo().parse(transactions.get(0).getData());
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		if (!transactions.get(0).getDataClassName().equals("ContractEventInfo")) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		// NotNull checks
		if (contractEventInfo.getContractTokenid() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid contract tokenid");
			return SolidityState.getFailState();
		}

		new Utils().checkContractBase(contractEventInfo,
				store.getTokenID(contractEventInfo.getContractTokenid()).get(0));

		return SolidityState.getSuccessState();
	}

	public SolidityState checkFormalOrderOpSolidity(Block block, boolean throwExceptions) throws BlockStoreException {

		// No output creation
		if (!block.getTransactions().get(0).getOutputs().isEmpty()) {
			if (throwExceptions)
				throw new TransactionOutputsDisallowedException();
			return SolidityState.getFailState();
		}

		Transaction tx = block.getTransactions().get(0);
		if (tx.getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		OrderCancelInfo info = null;
		try {
			info = new OrderCancelInfo().parse(tx.getData());
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		// NotNull checks
		if (info.getBlockHash() == null) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Invalid target txhash");
			return SolidityState.getFailState();
		}

		return SolidityState.getSuccessState();
	}

	public SolidityState checkFormalRewardSolidity(Block block, boolean throwExceptions) throws BlockStoreException {
		List<Transaction> transactions = block.getTransactions();

		if (transactions.size() != 1) {
			if (throwExceptions)
				throw new IncorrectTransactionCountException();
			return SolidityState.getFailState();
		}

		// No output creation
		if (!transactions.get(0).getOutputs().isEmpty()) {
			if (throwExceptions)
				throw new TransactionOutputsDisallowedException();
			return SolidityState.getFailState();
		}

		if (transactions.get(0).getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		// Check that the tx has correct data
		RewardInfo rewardInfo = new RewardInfo().parseChecked(transactions.get(0).getData());
		// NotNull checks
		if (rewardInfo.getPrevRewardHash() == null) {
			if (throwExceptions)
				throw new MissingDependencyException();
			return SolidityState.getFailState();
		}

		return SolidityState.getSuccessState();
	}

	public SolidityState checkFormalTokenSolidity(Block block, boolean throwExceptions) throws BlockStoreException {

		if (!block.getTransactions().get(0).isCoinBase()) {
			if (throwExceptions)
				throw new NotCoinbaseException();
			return SolidityState.getFailState();
		}

		Transaction tx = block.getTransactions().get(0);
		if (tx.getData() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		TokenInfo currentToken = null;
		try {
			currentToken = new TokenInfo().parse(tx.getData());
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		if (checkFormalTokenFields(throwExceptions, currentToken) == SolidityState.getFailState())
			return SolidityState.getFailState();

		// Check field correctness: amount
		if (!currentToken.getToken().getAmount().equals(block.getTransactions().get(0).getOutputSum())) {
			if (throwExceptions)
				throw new InvalidTransactionDataException("Incorrect amount field");
			return SolidityState.getFailState();
		}

		// Check all token issuance transaction outputs are actually of the
		// given token
		for (Transaction tx1 : block.getTransactions()) {
			for (TransactionOutput out : tx1.getOutputs()) {
				if (!out.getValue().getTokenHex().equals(currentToken.getToken().getTokenid())
						&& !out.getValue().isBIG()) {
					if (throwExceptions)
						throw new InvalidTokenOutputException();
					return SolidityState.getFailState();
				}
			}
		}

		// Must define enough permissioned addresses
		if (currentToken.getToken().getSignnumber() > currentToken.getMultiSignAddresses().size()) {
			if (throwExceptions)
				throw new InvalidTransactionDataException(
						"Cannot fulfill required sign number from multisign address list");
			return SolidityState.getFailState();
		}

		// Ensure signatures exist
		if (tx.getDataSignature() == null) {
			if (throwExceptions)
				throw new MissingTransactionDataException();
			return SolidityState.getFailState();
		}

		// Get signatures from transaction
		String jsonStr = new String(tx.getDataSignature());
		try {
			jsonmapper.readValue(jsonStr, MultiSignByRequest.class);
		} catch (IOException e) {
			if (throwExceptions)
				throw new MalformedTransactionDataException();
			return SolidityState.getFailState();
		}

		return SolidityState.getSuccessState();
	}

	public void checkTokenUnique(Block block, BlockStoreInterface store)
			throws BlockStoreException, JsonParseException, JsonMappingException, IOException {
		/*
		 * Token is unique with token name and domain
		 */
		TokenInfo currentToken = new TokenInfo().parse(block.getTransactions().get(0).getData());
		if (store.getTokennameAndDomain(currentToken.getToken().getTokenname(),
				currentToken.getToken().getDomainNameBlockHash()) && currentToken.getToken().getTokenindex() == 0) {
			throw new VerificationException(" Token name and domain exists.");
		}
	}

	/*
	 * Checks if the block is valid based on itself and its dependencies. Rechecks
	 * formal criteria too. If SolidityState.getSuccessState() is returned, the
	 * block is valid. If SolidityState.getFailState() is returned, the block is
	 * invalid. Otherwise, appropriate solidity states are returned to imply missing
	 * dependencies.
	 */
	private SolidityState checkFullBlockSolidity(Block block, boolean throwExceptions, List<BlockWrap> allPredecessors,
			boolean allowMissingPredecessor, BlockStoreInterface store) {
		return checkFullBlockSolidity(block, throwExceptions, allPredecessors, allowMissingPredecessor, store, false);
	}

	private SolidityState checkFullBlockSolidity(Block block, boolean throwExceptions, List<BlockWrap> allPredecessors,
			boolean allowMissingPredecessor, BlockStoreInterface store, boolean batch) {
		try {
			ServiceBaseConnect servicebase = new ServiceBaseConnect(serverConfiguration, networkParameters,
					cacheBlockService, jsonmapper);
			BlockWrap storedPrev = servicebase.getBlockWrap(block.getPrevBlockHash(), store);
			BlockWrap storedPrevBranch = servicebase.getBlockWrap(block.getPrevBranchBlockHash(), store);

			if (block.getHash() == Sha256Hash.ZERO_HASH) {
				if (throwExceptions)
					throw new VerificationException("Lucky zeros not allowed");
				return SolidityState.getFailState();
			}
			// Check predecessor blocks exist
			if (storedPrev == null && !allowMissingPredecessor) {
				return SolidityState.from(block.getPrevBlockHash(), true);
			}
			if (storedPrevBranch == null && !allowMissingPredecessor) {
				return SolidityState.from(block.getPrevBranchBlockHash(), true);
			}
			if (block.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
				if (throwExceptions)
					throw new GenesisBlockDisallowedException();
				return SolidityState.getFailState();
			}

			// Check height, all required max +1
			/*
			 * if (block.getHeight() != calcHeightRequiredBlocks(block, allPredecessors,
			 * store)) { if (throwExceptions) throw new
			 * VerificationException("Wrong height"); return SolidityState.getFailState(); }
			 */
			// Disallow someone burning other people's orders
			if (block.getBlockType() != BlockType.BLOCKTYPE_ORDER_OPEN) {
				for (Transaction tx : block.getTransactions())
					if (tx.getDataClassName() != null && tx.getDataClassName().equals("OrderOpen")) {
						if (throwExceptions)
							throw new MalformedTransactionDataException();
						return SolidityState.getFailState();
					}
			}
			if (!allowMissingPredecessor) {
				// Check timestamp: enforce monotone time increase
				if (block.getTimeSeconds() < storedPrev.getBlock().getTimeSeconds()
						|| block.getTimeSeconds() < storedPrevBranch.getBlock().getTimeSeconds()) {
					if (throwExceptions)
						throw new TimeReversionException();
					return SolidityState.getFailState();
				}

				// PoW difficulty/consensus inheritance is not enforced for
			// non-beacon blocks in PoS mode — Casper/GHOST handle fork
			// choice and finality independently of lastMiningRewardBlock
			// and difficultyTarget, which are vestigial PoW fields.
			}
			// Check transactions are solid
			SolidityState transactionalSolidityState = checkFullTransactionalSolidity(block, block.getHeight(),
					throwExceptions, store, batch);
			if (!(transactionalSolidityState.getState() == State.Success)) {
				return transactionalSolidityState;
			}

			// Check type-specific solidity
			SolidityState typeSpecificSolidityState = checkFullTypeSpecificSolidity(block, storedPrev, storedPrevBranch,
					block.getHeight(), throwExceptions, store);
			if (!(typeSpecificSolidityState.getState() == State.Success)) {
				return typeSpecificSolidityState;
			}

			return SolidityState.getSuccessState();
		} catch (VerificationException e) {
			throw e;
		} catch (Exception e) {
			logger.error("Unhandled exception in checkSolidity: ", e);
			if (throwExceptions)
				throw new VerificationException(e);
			return SolidityState.getFailState();
		}
	}

	private boolean checkUnique(List<TransactionOutPoint> allInputTx, TransactionOutPoint t) {
		for (TransactionOutPoint out : allInputTx) {
			if (t.getTxHash().equals(out.getTxHash()) && t.getBlockHash().equals(out.getBlockHash())
					&& t.getIndex() == out.getIndex()) {
				return true;
			}
		}
		return false;

	}

	/*
	 * Checks if the block is formally correct without relying on predecessors
	 */
	public SolidityState checkFormalBlockSolidity(Block block, boolean throwExceptions) {
		try {
			if (block.getHash() == Sha256Hash.ZERO_HASH) {
				if (throwExceptions)
					throw new VerificationException("Lucky zeros not allowed");
				return SolidityState.getFailState();
			}

			if (block.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
				if (throwExceptions)
					throw new GenesisBlockDisallowedException();
				return SolidityState.getFailState();
			}

			// Disallow someone burning other people's orders
			if (block.getBlockType() != BlockType.BLOCKTYPE_ORDER_OPEN) {
				for (Transaction tx : block.getTransactions())
					if (tx.getDataClassName() != null && tx.getDataClassName().equals("OrderOpen")) {
						if (throwExceptions)
							throw new MalformedTransactionDataException();
						return SolidityState.getFailState();
					}
			}

			// Check transaction solidity
			SolidityState transactionalSolidityState = checkFormalTransactionalSolidity(block, throwExceptions);
			if (!(transactionalSolidityState.getState() == State.Success)) {
				return transactionalSolidityState;
			}

			// Check type-specific solidity
			SolidityState typeSpecificSolidityState = checkFormalTypeSpecificSolidity(block, throwExceptions);
			if (!(typeSpecificSolidityState.getState() == State.Success)) {
				return typeSpecificSolidityState;
			}

			return SolidityState.getSuccessState();
		} catch (VerificationException e) {
			throw e;
		} catch (Exception e) {
			logger.error("Unhandled exception in checkSolidity: ", e);
			if (throwExceptions)
				throw new VerificationException(e);
			return SolidityState.getFailState();
		}
	}

	private SolidityState checkRequiredAndOk(Block block, boolean throwExceptions, List<BlockWrap> allRequirements,
			BlockStoreInterface store) throws BlockStoreException {
		//
		for (BlockWrap pred : allRequirements) {
				if (pred == null)
				return SolidityState.from(Sha256Hash.ZERO_HASH, true);
			if (pred.getBlock().getBlockType().requiresCalculation() && pred.getBlockEvaluation().getSolid() != 2)
				return SolidityState.fromMissingCalculation(pred.getBlockHash());
		}
		return SolidityState.getSuccessState();
	}

	public void checkBlockBeforeSave(Block block, BlockStoreInterface store) throws BlockStoreException {

		block.verifyHeader();
		// Layer scoping: a node only accepts block types allowed by its
		// NetworkParameters. Layer 0 nodes accept the full settlement set; a
		// Layer 1 sub-chain node rejects types that belong to another layer.
		// See LAYERING-PLAN.md. (No-op for L0 today, which allows all types.)
		if (!networkParameters.getAllowedBlockTypes().contains(block.getBlockType())) {
			throw new VerificationException(
					"Block type " + block.getBlockType() + " is not allowed on chain " + networkParameters.getChainId());
		}
		if (!checkSpentAndConflict(new HashSet<BlockWrap>(), initBlockWrap(block), false, store))
			throw new ConflictPossibleException("Conflict Possible");
		checkDomainname(block);
	}

	public void checkDomainname(Block block) {
		switch (block.getBlockType()) {
		case BLOCKTYPE_TOKEN_CREATION:
			TokenInfo currentToken = new TokenInfo().parseChecked(block.getTransactions().get(0).getData());
			if (TokenType.domainname.ordinal() == currentToken.getToken().getTokentype()) {
				if (!DomainValidator.getInstance().isValid(currentToken.getToken().getTokenname()))
					throw new VerificationException("Domain name is not valid.");
			}
			break;
		default:
			break;
		}
	}

	/*
	 * spendpending has timeout for 5 minute return false, if there is spendpending
	 * and timeout not
	 */
	public boolean checkSpendpending(UTXO output) {
		int SPENTPENDINGTIMEOUT = 300000;
		if (output.isSpendPending()) {
			return (System.currentTimeMillis() - output.getSpendPendingTime()) > SPENTPENDINGTIMEOUT;
		}
		return true;

	}

	ExecutorService scriptVerificationExecutor = Executors.newFixedThreadPool(
			Runtime.getRuntime().availableProcessors(), new ContextPropagatingThreadFactory("Script verification"));

	/**
	 * A job submitted to the executor which verifies signatures.
	 */
	private static class Verifier implements Callable<VerificationException> {
		final Transaction tx;
		final List<Script> prevOutScripts;
		final Set<VerifyFlag> verifyFlags;

		public Verifier(final Transaction tx, final List<Script> prevOutScripts, final Set<VerifyFlag> verifyFlags) {
			this.tx = tx;
			this.prevOutScripts = prevOutScripts;
			this.verifyFlags = verifyFlags;
		}

		@Nullable
		@Override
		public VerificationException call() throws Exception {
			try {
				ListIterator<Script> prevOutIt = prevOutScripts.listIterator();
				for (int index = 0; index < tx.getInputs().size(); index++) {
					tx.getInputs().get(index).getScriptSig().correctlySpends(tx, index, prevOutIt.next(), verifyFlags);
				}
			} catch (VerificationException e) {
				return e;
			}
			return null;
		}
	}

	public SolidityState checkRewardChainLink(Block rewardBlock, BlockStoreInterface store)
			throws BlockStoreException {
		RewardInfo rewardInfo = new RewardInfo().parseChecked(rewardBlock.getTransactions().get(0).getData());

		// Check previous reward blocks exist and get their approved sets
		Sha256Hash prevRewardHash = rewardInfo.getPrevRewardHash();
		if (prevRewardHash == null)
			throw new VerificationException("Missing previous reward block: " + prevRewardHash);

		Block prevRewardBlock = store.get(prevRewardHash);
		if (prevRewardBlock == null)
			return SolidityState.fromPrevReward(prevRewardHash, true);
		if (prevRewardBlock.getBlockType() != BlockType.BLOCKTYPE_BEACON
				&& prevRewardBlock.getBlockType() != BlockType.BLOCKTYPE_INITIAL)
			throw new VerificationException("Previous reward block is not reward block.");

		return SolidityState.getSuccessState();
	}

	public SolidityState checkRewardReferencedBlocks(Block rewardBlock, BlockStoreInterface store)
			throws BlockStoreException {
		try {
			RewardInfo rewardInfo = new RewardInfo().parseChecked(rewardBlock.getTransactions().get(0).getData());

			// Check previous reward blocks exist and get their approved sets
			Sha256Hash prevRewardHash = rewardInfo.getPrevRewardHash();
			if (prevRewardHash == null)
				throw new VerificationException("Missing previous block reference." + prevRewardHash);

			Block prevRewardBlock = store.get(prevRewardHash);
			if (prevRewardBlock == null)
				return SolidityState.fromPrevReward(prevRewardHash, true);
			if (prevRewardBlock.getBlockType() != BlockType.BLOCKTYPE_BEACON
					&& prevRewardBlock.getBlockType() != BlockType.BLOCKTYPE_INITIAL)
				throw new VerificationException("Previous reward block is not reward block.");

			// Get all blocks approved by previous reward blocks
			long cutoffHeight = getRewardCutoffHeight(prevRewardHash, store);

			for (Sha256Hash hash : rewardInfo.getBlocks()) {
				BlockWrap block = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,
						jsonmapper).getBlockWrap(hash, store);
				if (block == null)
					return SolidityState.fromReferenced(hash, true);
				if (block.getBlock().getHeight() <= cutoffHeight && cutoffHeight > 0)
					throw new VerificationException("Referenced blocks are below cutoff height.");
			}

		} catch (Exception e) {
			throw new VerificationException("checkRewardReferencedBlocks not completed:", e);
		}

		return SolidityState.getSuccessState();
	}

	public GetTXRewardResponse getMaxConfirmedReward(Map<String, Object> request, BlockStoreInterface store)
			throws BlockStoreException {

		return GetTXRewardResponse.create(cacheBlockService.getMaxConfirmedReward(store));

	}

	public GetTXRewardListResponse getAllConfirmedReward(Map<String, Object> request, BlockStoreInterface store)
			throws BlockStoreException {

		return GetTXRewardListResponse.create(store.getAllConfirmedReward());

	}

}
