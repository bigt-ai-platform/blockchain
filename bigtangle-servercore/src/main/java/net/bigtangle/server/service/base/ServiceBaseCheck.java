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
import net.bigtangle.utils.Json;

import net.bigtangle.core.Address;
import net.bigtangle.core.AttestationData;
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
import net.bigtangle.core.SlotData;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.TXReward;
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
		// Bonded stake outputs are unspendable until a withdrawal mechanism
		// exists; cache per-block so each input is checked once.
		java.util.Set<String> checkedBondedOutputs = new java.util.HashSet<>();
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
					String bondKey = in.getOutpoint().getBlockHash().toString()
							+ ":" + in.getOutpoint().getTxHash().toString();
					if (in.getOutpoint().getIndex() == 0 && checkedBondedOutputs.add(bondKey)) {
						try {
							net.bigtangle.core.StakeRecord bonded = store.getStakeDepositByOutput(
									in.getOutpoint().getBlockHash(), in.getOutpoint().getTxHash());
							if (bonded != null) {
								throw new InvalidTransactionException(
										"Cannot spend a bonded stake output: " + bondKey);
							}
						} catch (BlockStoreException e) {
							throw e;
						}
					}
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
			if (block.getBlockType().equals(BlockType.BLOCKTYPE_BEACON)
					|| block.getBlockType().equals(BlockType.BLOCKTYPE_SLASHING)
					|| block.getBlockType().equals(BlockType.BLOCKTYPE_EXIT)
					// CROSSTANGLE is a fee-less cross-chain VALUE message. It
					// must reach the type-specific handler (L0AnchorHandler)
					// instead of tripping NoFeeException before it runs, or the
					// block is never validated on the peer/gossip receive path.
					|| block.getBlockType().equals(BlockType.BLOCKTYPE_CROSSTANGLE)) {
				checkFee = true;
			}
			// Reward minting is ONLY valid in the first beacon of an epoch
			// (slot-in-epoch 0), so an arbitrary node cannot mint in any beacon.
			boolean beaconAtEpochStart = isBeaconAtEpochStart(block);
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
				// Epoch-reward transactions in BEACON blocks are a protocol mint:
				// zero inputs, freshly created outputs (validated by
				// checkFullRewardSolidity). They are exempt from the
				// input/output-conservation and script checks ONLY in the first
				// beacon of an epoch; anywhere else a zero-input value tx is
				// rejected exactly like any other.
				boolean mintTx = beaconAtEpochStart
						&& !isCoinBase && tx.getInputs().isEmpty();
				if (isCoinBase) {
					// coinbaseValue = valueOut;
				} else if (!mintTx) {
					if (checkTxInputOutput(valueIn, valueOut, block)) {
						checkFee = true;
					}
				}

				// STAKE deposit inputs are signed with the validator's
				// post-quantum (PQ) key, which by construction cannot satisfy a
				// standard P2PKH prevout (the founder's funding output). They are
				// validated authoritatively by checkStakeDepositSolidity (BLS
				// key + proof of possession + bonded-output binding) which runs
				// right after this generic check, so skip the generic script
				// verifier here — otherwise every STAKE block fails solidity and
				// its reference in a peer beacon blocks that beacon's confirm.
				if (!isCoinBase && !mintTx && block.getBlockType() != BlockType.BLOCKTYPE_STAKE) {
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
		case BLOCKTYPE_STAKE: {
			SolidityState stakeState = checkStakeDepositSolidity(block, throwExceptions, store);
			if (!(stakeState.getState() == State.Success)) {
				return stakeState;
			}
			break;
		}
		case BLOCKTYPE_SLASHING: {
			SolidityState slashState = checkSlashingSolidity(block, throwExceptions, store);
			if (!(slashState.getState() == State.Success)) {
				return slashState;
			}
			break;
		}
		case BLOCKTYPE_EXIT: {
			SolidityState exitState = checkExitSolidity(block, throwExceptions, store);
			if (!(exitState.getState() == State.Success)) {
				return exitState;
			}
			break;
		}
		default:
			throw new RuntimeException("No Implementation");
		}

		return SolidityState.getSuccessState();
	}

	/**
	 * Validates a BLOCKTYPE_EXIT block: the first transaction must carry an
	 * authenticated exit request (a signature over the pubkey by that pubkey's
	 * key), proving the validator itself requested the exit.
	 */
	private SolidityState checkExitSolidity(Block block, boolean throwExceptions) throws BlockStoreException {
		return checkExitSolidity(block, throwExceptions, null);
	}

	private SolidityState checkExitSolidity(Block block, boolean throwExceptions, BlockStoreInterface store)
			throws BlockStoreException {
		if (block.getTransactions() == null || block.getTransactions().isEmpty()) {
			if (throwExceptions)
				throw new BlockStoreException("EXIT block has no transactions");
			return SolidityState.getFailState();
		}
		Transaction tx = block.getTransactions().get(0);
		if (!net.bigtangle.server.service.StakeService.EXIT_DATA_CLASS.equals(tx.getDataClassName())
				|| tx.getData() == null) {
			if (throwExceptions)
				throw new BlockStoreException("EXIT block transaction is not an ExitRequest");
			return SolidityState.getFailState();
		}
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
			String pubkeyHex = (String) data.get("pubkey");
			byte[] signature = net.bigtangle.core.Utils.HEX.decode((String) data.get("signature"));
			Long nonce = data.get("nonce") != null
					? Long.parseLong(data.get("nonce").toString()) : null;
			if (pubkeyHex == null || signature == null || signature.length == 0 || nonce == null) {
				throw new BlockStoreException("EXIT request is missing pubkey, signature or nonce");
			}
			byte[] pubkey = net.bigtangle.core.Utils.HEX.decode(pubkeyHex);
			PQKey signer = PQKey.fromPublicOnly(pubkey);
			java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(pubkey.length + 8);
			buf.put(pubkey);
			buf.putLong(nonce);
			Sha256Hash msg = Sha256Hash.of(buf.array());
			if (!PQScriptUtils.verifyPQ(signer.getPublicKeyBytes(), signature, msg)) {
				throw new BlockStoreException("EXIT request signature is invalid");
			}
			// The nonce is bound to the block's own chain position: the parent
			// beacon's chainlength. This is fixed for the block (deterministic
			// on revalidation) and NOT submitter-choosable — the parent MUST be
			// a BEACON, otherwise the exit cannot be validated and is rejected.
			// The formal path (no store) skips the window.
			if (store != null) {
				// The validator must have an ACTIVE, not-slashed deposit — the
				// durable replay guard. isExiting() is NOT checked here: it is
				// mutable current state that would make the block fail its own
				// revalidation once applied; duplicate exits are handled
				// idempotently at apply time. The nonce window below is defense
				// in depth on top of this.
				net.bigtangle.core.StakeRecord dep = store.getStakeDeposit(pubkey);
				if (dep == null || dep.isSlashed() || dep.getActivatedEpoch() < 0) {
					throw new BlockStoreException("EXIT request has no active, exit-eligible deposit");
				}
				Block parent = store.get(block.getPrevBlockHash());
				if (parent == null) {
					return SolidityState.fromPrevReward(block.getPrevBlockHash(), true);
				}
				// The parent MUST be a beacon (or genesis at chain position 0),
				// so a submitter cannot point at an arbitrary block to escape
				// the nonce window.
				if (parent.getBlockType() != BlockType.BLOCKTYPE_BEACON
						&& parent.getBlockType() != BlockType.BLOCKTYPE_INITIAL) {
					throw new BlockStoreException("EXIT block parent is not a beacon");
				}
				long expectedNonce = 0;
				if (parent.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
					try {
						RewardInfo pri = new RewardInfo().parseChecked(parent.getTransactions().get(0).getData());
						expectedNonce = pri.getChainlength();
					} catch (Exception e) {
						return SolidityState.fromPrevReward(block.getPrevBlockHash(), true);
					}
				}
				if (Math.abs(nonce - expectedNonce) > 1) {
					throw new BlockStoreException("EXIT request nonce is stale");
				}
			}
		} catch (BlockStoreException e) {
			if (throwExceptions)
				throw e;
			return SolidityState.getFailState();
		} catch (Exception e) {
			if (throwExceptions)
				throw new BlockStoreException("EXIT request data is malformed", e);
			return SolidityState.getFailState();
		}
		return SolidityState.getSuccessState();
	}

	/**
	 * Validates a BLOCKTYPE_SLASHING block: the first transaction must carry two
	 * authenticated attestations from the SAME validator that form a slashable
	 * pattern (double vote or surround vote). This makes slashing a consensus
	 * decision rather than a locally-observed mutation.
	 */
	private SolidityState checkSlashingSolidity(Block block, boolean throwExceptions)
			throws BlockStoreException {
		return checkSlashingSolidity(block, throwExceptions, null);
	}

	private SolidityState checkSlashingSolidity(Block block, boolean throwExceptions, BlockStoreInterface store)
			throws BlockStoreException {
		if (block.getTransactions() == null || block.getTransactions().isEmpty()) {
			if (throwExceptions)
				throw new BlockStoreException("SLASHING block has no transactions");
			return SolidityState.getFailState();
		}
		Transaction tx = block.getTransactions().get(0);
		if (!net.bigtangle.server.service.StakeService.SLASHING_DATA_CLASS.equals(tx.getDataClassName())
				|| tx.getData() == null) {
			if (throwExceptions)
				throw new BlockStoreException("SLASHING block transaction is not a SlashingProof");
			return SolidityState.getFailState();
		}
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
			if (Boolean.TRUE.equals(data.get("proposal"))) {
				// Proposal equivocation: two different signed SlotDatas, same
				// slot, both authentic under the elected proposer's key.
				net.bigtangle.core.SlotData sd1 = Json.jsonmapper().convertValue(data.get("slotData1"),
						net.bigtangle.core.SlotData.class);
				net.bigtangle.core.SlotData sd2 = Json.jsonmapper().convertValue(data.get("slotData2"),
						net.bigtangle.core.SlotData.class);
				if (sd1 == null || sd2 == null || sd1.getSlot() != sd2.getSlot()) {
					throw new BlockStoreException("SLASHING proposal proof malformed");
				}
				if (store != null) {
					byte[] proposer = net.bigtangle.server.service.StakeService
							.expectedProposerPubkey(sd1.getSlot(), store);
					if (proposer == null || !net.bigtangle.server.service.StakeService
							.isProposalEquivocation(sd1, sd2, proposer)) {
						throw new BlockStoreException("SLASHING proposal proof is not an authenticated equivocation");
					}
				}
			} else {
			AttestationData att1 = Json.jsonmapper().convertValue(data.get("attestation1"), AttestationData.class);
			AttestationData att2 = Json.jsonmapper().convertValue(data.get("attestation2"), AttestationData.class);
			if (att1 == null || att2 == null || att1.getValidatorPubkey() == null
					|| !java.util.Arrays.equals(att1.getValidatorPubkey(), att2.getValidatorPubkey())) {
				throw new BlockStoreException("SLASHING proof must contain two attestations from the same validator");
			}
			// Both attestations must be AUTHENTICATED, otherwise anyone could
			// forge two unsigned attestations with a victim's pubkey and slash
			// the victim on every node.
			if (!att1.verifySignature() || !att2.verifySignature()) {
				throw new BlockStoreException("SLASHING proof attestations are not authenticated");
			}
			boolean doubleVote = net.bigtangle.server.service.SlashingService.isDoubleVote(att1, att2);
			boolean surround = net.bigtangle.server.service.SlashingService.isSurroundVote(att1, att2);
			if (!doubleVote && !surround) {
				throw new BlockStoreException("SLASHING proof does not form a double or surround vote");
			}
			}
		} catch (BlockStoreException e) {
			if (throwExceptions)
				throw e;
			return SolidityState.getFailState();
		} catch (Exception e) {
			if (throwExceptions)
				throw new BlockStoreException("SLASHING proof data is malformed", e);
			return SolidityState.getFailState();
		}
		// The parent MUST be a beacon (or genesis) so the block is structurally
		// well-formed. The withdrawable epoch is NOT derived from the parent — it
		// comes from the CONFIRMING beacon's chain epoch at confirm time, which
		// is fixed, current, and not submitter-chosen — so no recency bound on
		// the parent is needed here.
		if (store != null) {
			Block parent = store.get(block.getPrevBlockHash());
			if (parent == null) {
				return SolidityState.fromPrevReward(block.getPrevBlockHash(), true);
			}
			if (parent.getBlockType() != BlockType.BLOCKTYPE_BEACON
					&& parent.getBlockType() != BlockType.BLOCKTYPE_INITIAL) {
				throw new BlockStoreException("SLASHING block parent is not a beacon");
			}
		}
		return SolidityState.getSuccessState();
	}

	/**
	 * True when the beacon is the FIRST beacon of its epoch. Classification is
	 * SLOT-based (the proposer-signed SlotData slot % SLOTS_PER_EPOCH == 0, see
	 * {@link net.bigtangle.server.service.SlotService#isEpochStartBeacon}), so a
	 * missed slot can never permanently misalign rewards from the reward
	 * chainlength. The proposer signature over the SlotData (verified in this
	 * same validation pass) makes the declared slot unforgeable, so a mid-epoch
	 * beacon cannot claim epoch start and mint rewards. Reward minting is only
	 * permitted here.
	 */
	private boolean isBeaconAtEpochStart(Block block) {
		if (block.getBlockType() != BlockType.BLOCKTYPE_BEACON || block.getTransactions() == null
				|| block.getTransactions().isEmpty()) {
			return false;
		}
		try {
			RewardInfo ri = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
			return net.bigtangle.server.service.SlotService.isEpochStartBeacon(block, ri);
		} catch (Exception e) {
			return false;
		}
	}

	/** The beacon's SlotData (from its SlotData transaction), or null if absent/unparseable. */
	private net.bigtangle.core.SlotData slotDataOf(Block block) {
		if (block.getBlockType() != BlockType.BLOCKTYPE_BEACON || block.getTransactions() == null) {
			return null;
		}
		try {
			for (Transaction tx : block.getTransactions()) {
				if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
					return Json.jsonmapper().readValue(tx.getData(), net.bigtangle.core.SlotData.class);
				}
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	/** The fee-pool snapshot committed in the beacon's SlotData, or null if absent. */
	private Long committedFeePool(Block block) {
		if (block.getBlockType() != BlockType.BLOCKTYPE_BEACON || block.getTransactions() == null) {
			return null;
		}
		try {
			for (Transaction tx : block.getTransactions()) {
				if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
					net.bigtangle.core.SlotData sd = Json.jsonmapper().readValue(tx.getData(),
							net.bigtangle.core.SlotData.class);
					if (sd != null) {
						return sd.getFeePool();
					}
				}
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	/**
	 * Verifies the beacon is AUTHENTICATED: the signer must be EXACTLY the
	 * validator the deterministic proposer selection picks for the declared slot
	 * (using the chain's RANDAO mix). There is no "any active validator"
	 * fallback — a beacon signed by anyone who is not the slot's proposer is
	 * rejected, so proposer identity is actually enforced. A beacon WITHOUT
	 * SlotData is rejected outright once past the PoS activation height; below
	 * it (test / pre-PoS chains) legacy beacons are tolerated.
	 */
	private boolean verifyProposerSignature(Block block, BlockStoreInterface store) {
		if (block.getBlockType() != BlockType.BLOCKTYPE_BEACON) {
			return true;
		}
		try {
			net.bigtangle.core.SlotData sd = null;
			for (Transaction tx : block.getTransactions()) {
				if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
					sd = Json.jsonmapper().readValue(tx.getData(), net.bigtangle.core.SlotData.class);
					break;
				}
			}
			if (sd == null) {
				return legacyBeaconAllowed(block);
			}
			if (sd.getProposerSignature() == null || sd.getProposerSignature().length == 0) {
				return false;
			}
			// Use the SNAPSHOTTED active validator set from two epochs earlier
			// (same boundary discipline as mixfinal_), never the node's live local
			// set — the expected proposer must be a fixed chain fact.
			List<StakeRecord> active = validatorsForEpoch(sd.getSlot() / 32 - 2, store);
			if (active.isEmpty()) {
				return false;
			}
			// FINALIZED RANDAO snapshot (pos_state mixfinal_) or the deterministic
			// default, matching RandaoService.getSelectionMix. The snapshot is
			// written once at the epoch boundary and is IMMUTABLE, so proposer
			// identity is identical on every node regardless of local confirmation
			// progress or late-confirming beacons.
			long laggedEpoch = sd.getSlot() / 32 - 2;
			byte[] mix = laggedEpoch >= 0 ? store.getPosState("randao", "mixfinal_" + laggedEpoch) : null;
			if (mix == null) {
				mix = sha256DefaultMix(laggedEpoch);
			}
			long expectedIdx = net.bigtangle.server.service.SlotService.selectProposerForSlot(
					sd.getSlot(), active, mix);
			if (expectedIdx >= 0 && expectedIdx < active.size()
					&& verifySlotDataSignature(active.get((int) expectedIdx).getPubkey(), sd)) {
				return true;
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}

	private byte[] sha256DefaultMix(long epoch) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256")
					.digest(String.valueOf(epoch).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (Exception e) {
			return new byte[32];
		}
	}

	private boolean verifySlotDataSignature(byte[] pubkey, net.bigtangle.core.SlotData sd) {
		try {
			PQKey signer = PQKey.fromPublicOnly(pubkey);
			return PQScriptUtils.verifyPQ(signer.getPublicKeyBytes(), sd.getProposerSignature(),
					sd.getMessageHash());
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * RANDAO reveal is MANDATORY for every beacon carrying SlotData: the reveal
	 * must be the slot proposer's unique BLS signature over the slot (verified
	 * against the BLS public key registered in its STAKE deposit). A beacon whose
	 * reveal is absent or does not verify is REJECTED — a proposer can never fold
	 * grindable bytes into the mix, and proposer identity is computed from the
	 * IMMUTABLE finalized mix two epochs earlier so every node derives the same
	 * expected proposer.
	 * Beacons without a SlotData transaction (legacy / non-PoS) are accepted.
	 */
	private boolean verifyRandaoReveal(Block block, BlockStoreInterface store) {
		if (block.getBlockType() != BlockType.BLOCKTYPE_BEACON) {
			return true;
		}
		try {
			net.bigtangle.core.SlotData sd = null;
			for (Transaction tx : block.getTransactions()) {
				if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
					sd = Json.jsonmapper().readValue(tx.getData(), net.bigtangle.core.SlotData.class);
					break;
				}
			}
			if (sd == null) {
				// Legacy beacon without SlotData: tolerated ONLY below the PoS
				// activation chainlength (test / pre-PoS chains). Past the
				// activation height an unauthenticated beacon is rejected outright
				// — confirmation power is the privilege PoS exists to protect.
				return legacyBeaconAllowed(block);
			}
			byte[] reveal = sd.getRandaoReveal();
			if (reveal == null || reveal.length == 0) {
				return false; // reveal is mandatory
			}
			// Slot sanity is CHAIN-DERIVED: the declared epoch must equal slot/32
			// and slots must strictly increase along the reward chain. The slot is
			// deliberately NOT bound to the reward chainlength — chainlength lags
			// the slot after any missed slot, and such a binding would reject
			// every beacon at the next epoch boundary and halt the chain. The
			// proposer signature + RANDAO reveal already bind this beacon to the
			// declared slot's elected proposer.
			if (!net.bigtangle.server.service.SlotService.slotSequenceValid(sd.getSlot(), sd.getEpoch(),
					prevBeaconSlot(block, store))) {
				return false;
			}
			// Use the SNAPSHOTTED active validator set from two epochs earlier
			// (same boundary discipline as mixfinal_), never the node's live set.
			List<StakeRecord> active = validatorsForEpoch(sd.getSlot() / 32 - 2, store);
			if (active.isEmpty()) {
				return false;
			}
			// IMMUTABLE FINALIZED mix from two epochs earlier (matching
			// RandaoService.getSelectionMix), so the expected proposer is a fixed
			// chain fact, identical on every node and immune to late beacons.
			long laggedEpoch = sd.getSlot() / 32 - 2;
			byte[] mix = laggedEpoch >= 0 ? store.getPosState("randao", "mixfinal_" + laggedEpoch) : null;
			if (mix == null) {
				mix = sha256DefaultMix(laggedEpoch);
			}
			long expectedIdx = net.bigtangle.server.service.SlotService.selectProposerForSlot(
					sd.getSlot(), active, mix);
			if (expectedIdx < 0 || expectedIdx >= active.size()) {
				return false;
			}
			byte[] proposerPubkey = active.get((int) expectedIdx).getPubkey();
			// Proposer identity (PQ signature over the SlotData) AND the reveal
			// (unique BLS signature over the slot) must both be by the registered
			// slot proposer — the BLS uniqueness is what forces the reveal bytes.
			net.bigtangle.core.StakeRecord dep = store.getStakeDeposit(proposerPubkey);
			if (dep == null || dep.getBlsPubkey() == null) {
				return false; // proposer has no registered BLS key
			}
			return verifySlotDataSignature(proposerPubkey, sd)
					&& net.bigtangle.server.service.RandaoService.verifyReveal(
							dep.getBlsPubkey(), sd.getSlot(), reveal)
					&& verifyEmbeddedAttestations(sd);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Inclusion integrity: the committed attestation root must equal the
	 * deterministic root over the embedded attestations, and every embedded
	 * attestation must carry a valid BLS signature. A proposer cannot claim a
	 * root that does not match the attestations it actually includes.
	 */
	private boolean verifyEmbeddedAttestations(SlotData sd) {
		net.bigtangle.core.Sha256Hash committed = sd.getAttestationRoot();
		net.bigtangle.core.Sha256Hash actual = net.bigtangle.server.service.CasperService
				.computeAttestationRoot(sd.getAttestations());
		if (!actual.equals(committed != null ? committed : net.bigtangle.core.Sha256Hash.ZERO_HASH)) {
			return false;
		}
		if (sd.getAttestations() != null) {
			for (AttestationData a : sd.getAttestations()) {
				if (a == null || !a.verifySignature()) {
					return false;
				}
			}
		}
		return true;
	}

	private byte[] sha256(byte[] input) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(input);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * A beacon without SlotData is only accepted strictly below the PoS
	 * activation chainlength. The position is the chain-derived REWARD chain
	 * length (RewardInfo.getChainlength), never the block's self-declared height.
	 */
	private boolean legacyBeaconAllowed(Block block) {
		long chainlength = rewardChainlength(block);
		return chainlength > 0 && chainlength < NetworkParameters.posAttestationActivation();
	}

	/**
	 * The signed slot of the beacon's prev reward beacon, or -1 when the prev
	 * beacon carries no SlotData (legacy/genesis) or is not yet stored — in
	 * which case the monotone-slot check is skipped (missing dependencies are
	 * handled by the dependency solidity states).
	 */
	private long prevBeaconSlot(Block block, BlockStoreInterface store) {
		try {
			net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
					.parseChecked(block.getTransactions().get(0).getData());
			if (ri == null || ri.getPrevRewardHash() == null) {
				return -1;
			}
			Block prev = store.get(ri.getPrevRewardHash());
			if (prev == null || prev.getTransactions() == null) {
				return -1;
			}
			for (Transaction tx : prev.getTransactions()) {
				if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
					net.bigtangle.core.SlotData psd = Json.jsonmapper().readValue(tx.getData(),
							net.bigtangle.core.SlotData.class);
					return psd != null ? psd.getSlot() : -1;
				}
			}
			return -1;
		} catch (Exception e) {
			return -1;
		}
	}

	/** The beacon's reward chainlength, or -1 if it cannot be derived. */
	private long rewardChainlength(Block block) {
		try {
			net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
					.parseChecked(block.getTransactions().get(0).getData());
			return ri != null ? ri.getChainlength() : -1;
		} catch (Exception e) {
			return -1;
		}
	}

	/**
	 * Active validators for proposer selection of a slot in {@code sourceEpoch + 2}:
	 * the immutable snapshot written at the epoch boundary, or (bootstrap / a node
	 * that has not crossed the boundary) the live active set.
	 */
	private List<StakeRecord> validatorsForEpoch(long sourceEpoch, BlockStoreInterface store) {
		List<StakeRecord> snap = net.bigtangle.server.service.SlotService.getValidatorSnapshot(sourceEpoch, store);
		// An empty snapshot is treated as missing (matching
		// SlotService.selectionValidators): an empty frozen set would make every
		// beacon of the epoch unproposable — an unrecoverable halt.
		if (snap != null && !snap.isEmpty()) {
			return snap;
		}
		try {
			// Activation-delay aware: the live fallback set only includes
			// validators active as of the current chain epoch.
			return store.getActiveStakeDeposits(
					net.bigtangle.server.service.SlotService.currentChainEpoch(store));
		} catch (Exception e) {
			return new java.util.ArrayList<>();
		}
	}

	/**
	 * Structural validation of a STAKE deposit block: the first transaction must
	 * be a well-formed {@code StakeDeposit} (data payload present and parseable,
	 * a BIG output of at least the minimum stake). This makes synced STAKE
	 * blocks valid inputs to the chain-derived validator set.
	 */
	private SolidityState checkStakeDepositSolidity(Block block, boolean throwExceptions,
			BlockStoreInterface store) throws BlockStoreException {
		if (block.getTransactions() == null || block.getTransactions().isEmpty()) {
			if (throwExceptions)
				throw new BlockStoreException("STAKE block has no transactions");
			return SolidityState.getFailState();
		}
		Transaction tx = block.getTransactions().get(0);
		if (!net.bigtangle.server.service.StakeService.STAKE_DATA_CLASS.equals(tx.getDataClassName())
				|| tx.getData() == null) {
			if (throwExceptions)
				throw new BlockStoreException("STAKE block transaction is not a StakeDeposit");
			return SolidityState.getFailState();
		}
		byte[][] depositParts;
		try {
			depositParts = net.bigtangle.server.service.StakeService.parseStakeDepositData(tx.getData());
		} catch (Exception e) {
			if (throwExceptions)
				throw new BlockStoreException("STAKE block deposit data is malformed", e);
			return SolidityState.getFailState();
		}
		byte[] declaredPubkey = depositParts[0];
		// The registered BLS key must be a well-formed G1 point and proven held
		// by the depositor (proof of possession over the ML-DSA pubkey). Without
		// this, a MIN_STAKE deposit with a garbage BLS key creates a permanently
		// unfillable proposer slot (verifyRandaoReveal would reject every beacon
		// it lands on), and a rogue key can be used to cancel others' reveals.
		byte[] declaredBlsPubkey = depositParts[1];
		byte[] declaredPop = depositParts[2];
		if (!net.bigtangle.server.service.RandaoService.isValidBlsPubkey(declaredBlsPubkey)
				|| !net.bigtangle.server.service.RandaoService.verifyProofOfPossession(
						declaredBlsPubkey, declaredPubkey, declaredPop)) {
			if (throwExceptions)
				throw new BlockStoreException("STAKE deposit has no valid BLS key / proof of possession");
			return SolidityState.getFailState();
		}
		// Reject a BLS key already registered by a DIFFERENT validator: shared
		// keys fold identical reveals that cancel in the XOR mix. The depositor's
		// own record is excluded so a top-up of its own key is not rejected.
		if (store != null) {
			for (net.bigtangle.core.StakeRecord other : store.getAllStakeDeposits()) {
				if (java.util.Arrays.equals(other.getPubkey(), declaredPubkey)) {
					continue; // own record — top-up / reorg re-apply
				}
				if (other.getBlsPubkey() != null
						&& java.util.Arrays.equals(other.getBlsPubkey(), declaredBlsPubkey)) {
					if (throwExceptions)
						throw new BlockStoreException("STAKE deposit reuses another validator's BLS key");
					return SolidityState.getFailState();
				}
			}
		}
		byte[] declaredHash = Utils.sha256hash160(declaredPubkey);
		boolean foundBondedOutput = false;
		for (TransactionOutput out : tx.getOutputs()) {
			if (!out.getValue().isBIG()) {
				continue;
			}
			if (out.getValue().getValue().compareTo(net.bigtangle.server.service.StakeService.MIN_STAKE) < 0) {
				continue;
			}
			// The bonded output MUST pay the declared validator pubkey so the
			// pubkey and the locked funds are bound together.
			try {
				byte[] outHash = out.getScriptPubKey().getPubKeyHash();
				if (!java.util.Arrays.equals(outHash, declaredHash)) {
					if (throwExceptions)
						throw new BlockStoreException(
								"STAKE bonded output does not pay the declared validator pubkey");
					return SolidityState.getFailState();
				}
			} catch (Exception e) {
				if (throwExceptions)
					throw new BlockStoreException("STAKE bonded output script is not P2PKH", e);
				return SolidityState.getFailState();
			}
			foundBondedOutput = true;
			break;
		}
		if (!foundBondedOutput) {
			if (throwExceptions)
				throw new BlockStoreException("STAKE block has no bonded BIG output of minimum stake");
			return SolidityState.getFailState();
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

		// A chain-connected reward (BEACON) block always carries a RewardInfo
		// in its first transaction (SlotService beacon: exactly 1 tx). The
		// epoch reward block additionally carries coinbase-style reward-output
		// transactions (one per validator) after the RewardInfo tx.
		if (transactions.isEmpty()) {
			if (throwExceptions)
				throw new IncorrectTransactionCountException();
			return SolidityState.getFailState();
		}

		// No output creation in the RewardInfo transaction itself
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

		// Reward-output transactions (epoch reward block) must be coinbase-like:
		// no inputs, no data, only freshly minted outputs. The SlotData tx
		// (which carries slot/randao/fee-pool info) is exempt.
		boolean hasRewardOutputs = false;
		for (int i = 1; i < transactions.size(); i++) {
			Transaction rewardTx = transactions.get(i);
			if ("SlotData".equals(rewardTx.getDataClassName())) {
				continue;
			}
			if (!rewardTx.getInputs().isEmpty() || rewardTx.getData() != null || rewardTx.getOutputs().isEmpty()) {
				if (throwExceptions)
					throw new IncorrectTransactionCountException();
				return SolidityState.getFailState();
			}
			hasRewardOutputs = true;
		}

		// The beacon must be AUTHENTICATED: the declared proposer's signature
		// over the SlotData binds the slot, fee pool and RANDAO reveal. Without
		// it, anyone could publish a beacon declaring any slot/fee-pool and
		// mint arbitrary value or confirm arbitrary DAG blocks. Enforced for
		// EVERY beacon, not only when the beacon mints.
		if (!verifyProposerSignature(block, store)) {
			if (throwExceptions)
				throw new BlockStoreException("Beacon is not signed by a valid proposer");
			return SolidityState.getFailState();
		}

		// RANDAO reveal is MANDATORY for every beacon carrying SlotData: the
		// reveal must be a signature over the slot by the registered proposer and
		// bound to a 32-byte commitment. A beacon without a valid reveal/commit
		// pair is rejected, so a proposer can never fold grindable bytes into the
		// mix.
		if (!verifyRandaoReveal(block, store)) {
			if (throwExceptions)
				throw new BlockStoreException("Beacon has an invalid or missing RANDAO reveal");
			return SolidityState.getFailState();
		}

		// Every reward output must pay a KNOWN depositor's address (past or
		// present), so a malicious proposer cannot mint to arbitrary addresses.
		// Using the full deposit set avoids false rejection when validators join
		// or leave between reward computation and validation.
		try {
			java.util.Set<String> depositorAddresses = new java.util.HashSet<>();
			for (net.bigtangle.core.StakeRecord v : store.getAllStakeDeposits()) {
				depositorAddresses.add(net.bigtangle.core.Address
						.fromHash160(networkParameters, Utils.sha256hash160(v.getPubkey())).toBase58());
			}
			for (int i = 1; i < transactions.size(); i++) {
				for (TransactionOutput out : transactions.get(i).getOutputs()) {
					String toAddr = null;
					try {
						toAddr = out.getScriptPubKey().getToAddress(networkParameters).toBase58();
					} catch (Exception e) {
						if (throwExceptions)
							throw new InvalidTransactionException(
									"Epoch reward output is not payable to a standard address");
						return SolidityState.getFailState();
					}
					if (!depositorAddresses.contains(toAddr)) {
						if (throwExceptions)
							throw new InvalidTransactionException(
									"Epoch reward output is not payable to a known validator");
						return SolidityState.getFailState();
					}
				}
			}
		} catch (BlockStoreException e) {
			// A DB error while checking recipients is fail-closed: reject.
			if (throwExceptions)
				throw e;
			return SolidityState.getFailState();
		}

		// Check that the tx has correct data
		RewardInfo rewardInfo = new RewardInfo().parseChecked(transactions.get(0).getData());

		// Exact mint validation: the proposer commits the fee-pool snapshot in
		// the beacon's SlotData; the reward outputs must EXACTLY equal it, must
		// not exceed the total active stake (a protocol cap), and a DB failure
		// here is fail-closed (reject), never a bypass.
		java.math.BigInteger rewardTotal = java.math.BigInteger.ZERO;
		for (int i = 1; i < transactions.size(); i++) {
			for (TransactionOutput out : transactions.get(i).getOutputs()) {
				if (out.getValue().isBIG()) {
					rewardTotal = rewardTotal.add(out.getValue().getValue());
				}
			}
		}
		try {
			java.math.BigInteger activeStake = java.math.BigInteger.ZERO;
			for (net.bigtangle.core.StakeRecord v : store.getActiveStakeDeposits(
					net.bigtangle.server.service.SlotService.currentChainEpoch(store))) {
				activeStake = activeStake.add(v.getAmount());
			}
			// Deterministic recomputation: the reward must equal the sum of fee
			// surpluses over the CONFIRMED blocks this beacon references in
			// RewardInfo.getBlocks — a pure function of chain state. Missing
			// data (a referenced block, its confirmation, or an input UTXO that
			// is not yet available locally) DEFERS with a solidity-missing state
			// so a lagging node never permanently rejects a valid beacon; only a
			// provable mismatch (null block set, out-of-window or already-
			// rewarded block, wrong reward total) rejects.
			if (hasRewardOutputs) {
				RewardInfo ri = new RewardInfo().parseChecked(transactions.get(0).getData());
				if (ri == null || ri.getBlocks() == null) {
					throw new InvalidTransactionException(
							"Epoch reward beacon has no referenced block set to recompute against");
				}
				// Lower bound: the previous reward cutoff. If it cannot be
				// computed the node is behind — defer, never accept windowless.
				long cutoffHeight = 0;
				try {
					net.bigtangle.server.service.base.ServiceBaseConnect sbc =
							new net.bigtangle.server.service.base.ServiceBaseConnect(
									serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
					cutoffHeight = sbc.getRewardCutoffHeight(ri.getPrevRewardHash(), store);
				} catch (Exception e) {
					return SolidityState.fromPrevReward(ri.getPrevRewardHash(), true);
				}
				// The reward must be DISJOINT from the rewarded sets of the
				// preceding beacons within the cutoff window (walked back the
				// reward chain). Any block rewarded more than CHAINLENGTH_CUTOFF
				// beacons ago is already excluded by the height cutoff below, so
				// the walk is bounded to the last ~40 beacons — O(1) per beacon
				// validation, not O(chain length).
				// Bound the walk by ITERATION COUNT (CHAINLENGTH_CUTOFF), not by
				// beacon height, so it is correct regardless of whether a
				// referenced block is an ancestor of the beacon that rewarded it.
				// Reaching the budget is CLEAN termination (the last 40 beacons
				// are fully covered; older ones are excluded by the height
				// cutoff); only genuinely incomplete walks defer.
				java.util.Set<Sha256Hash> prevRewarded = new java.util.HashSet<>();
				java.util.Set<Sha256Hash> visitedBeacons = new java.util.HashSet<>();
				Sha256Hash cursor = ri.getPrevRewardHash();
				int walkCount = 0;
				boolean terminatedCleanly = false;
				while (cursor != null && walkCount < net.bigtangle.params.NetworkParameters.CHAINLENGTH_CUTOFF) {
					walkCount++;
					if (!visitedBeacons.add(cursor)) {
						return SolidityState.fromPrevReward(cursor, true); // cycle — defer
					}
					Block prevBeacon = store.get(cursor);
					if (prevBeacon == null) {
						return SolidityState.fromPrevReward(cursor, true);
					}
					if (prevBeacon.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
						terminatedCleanly = true;
						break; // genesis — no prior rewards
					}
					RewardInfo prevRi;
					try {
						prevRi = new RewardInfo().parseChecked(prevBeacon.getTransactions().get(0).getData());
					} catch (Exception e) {
						return SolidityState.fromPrevReward(cursor, true);
					}
					if (prevRi == null) {
						return SolidityState.fromPrevReward(cursor, true);
					}
					if (prevRi.getBlocks() != null
							&& net.bigtangle.server.service.SlotService.isEpochStartBeacon(prevBeacon, prevRi)) {
						// Only EPOCH-START beacons reward blocks (signed slot %
						// 32 == 0); a block referenced/confirmed by a mid-epoch
						// beacon is not yet rewarded and remains eligible for
						// this epoch's payout.
						prevRewarded.addAll(prevRi.getBlocks());
					}
					cursor = prevRi.getPrevRewardHash();
				}
				if (walkCount >= net.bigtangle.params.NetworkParameters.CHAINLENGTH_CUTOFF) {
					terminatedCleanly = true; // budget reached — covered the window
				}
				if (!terminatedCleanly) {
					return SolidityState.fromPrevReward(ri.getPrevRewardHash(), true);
				}
					java.math.BigInteger expectedFromBlocks = java.math.BigInteger.ZERO;
					for (Sha256Hash h : ri.getBlocks()) {
						Block referenced = store.get(h);
						if (referenced == null) {
							return SolidityState.fromReferenced(h, true);
						}
						net.bigtangle.core.BlockEvaluation be = store.getBlockEvaluationsByhashs(h);
						if (be == null || !be.isConfirmed()) {
							return SolidityState.fromReferenced(h, true);
						}
						if (prevRewarded.contains(h)) {
							throw new InvalidTransactionException(
									"Epoch reward beacon re-rewards a previously rewarded block");
						}
						if (cutoffHeight > 0 && referenced.getHeight() <= cutoffHeight) {
							throw new InvalidTransactionException(
									"Epoch reward beacon references a block outside the epoch window");
						}
						// Ancestry: a rewarded block must be BELOW the beacon's own
						// height (a strict predecessor in the DAG), so the height
						// cutoff is a sound lower bound on what may be rewarded.
						if (referenced.getHeight() >= block.getHeight()) {
							throw new InvalidTransactionException(
									"Epoch reward beacon references a block at or above its own height");
						}
					try {
						expectedFromBlocks = expectedFromBlocks.add(
								net.bigtangle.server.service.SlotService.computeFeeSurplus(referenced, store));
					} catch (BlockStoreException e) {
						// Missing input UTXO — the node is behind; defer.
						return SolidityState.fromReferenced(h, true);
					}
				}
				if (rewardTotal.compareTo(expectedFromBlocks) != 0) {
					if (throwExceptions)
						throw new InvalidTransactionException(
								"Epoch reward does not match fees of the referenced confirmed blocks");
					return SolidityState.getFailState();
				}
				// EXACT SPLIT: the pool must be distributed pro-rata over the
				// epoch's selection snapshot — recomputed here from chain state.
				// A proposer paying any validator (incl. itself) more than its
				// share, or minting non-BIG value, is rejected. Legacy beacons
				// without SlotData skip this (pre-PoS chains had no split rule).
				net.bigtangle.core.SlotData rsd = slotDataOf(block);
				if (rsd != null) {
					long rewardEpoch = rsd.getSlot() / 32 - 2;
					java.util.List<net.bigtangle.core.StakeRecord> rewardValidators = validatorsForEpoch(
							rewardEpoch, store);
					java.util.Set<String> rewardVoters = net.bigtangle.server.service.CasperService
							.votersForEpoch(rewardEpoch, store);
					java.util.Map<String, java.math.BigInteger> expectedSplit = net.bigtangle.server.service.EpochRewardService
							.planEpochRewards(expectedFromBlocks, rewardValidators, rewardVoters, networkParameters);
					java.util.Map<String, java.math.BigInteger> actualSplit = new java.util.HashMap<>();
					for (int i = 1; i < transactions.size(); i++) {
						Transaction rtx = transactions.get(i);
						if ("SlotData".equals(rtx.getDataClassName())) {
							continue;
						}
						for (TransactionOutput out : rtx.getOutputs()) {
							if (!out.getValue().isBIG()) {
								if (throwExceptions)
									throw new InvalidTransactionException(
											"Epoch reward output is not BIG — minting other tokens is not allowed");
								return SolidityState.getFailState();
							}
							String addr = out.getScriptPubKey().getToAddress(networkParameters).toBase58();
							actualSplit.merge(addr, out.getValue().getValue(), java.math.BigInteger::add);
						}
					}
					if (!expectedSplit.equals(actualSplit)) {
						if (throwExceptions)
							throw new InvalidTransactionException(
									"Epoch reward split does not match the stake-proportional plan");
						return SolidityState.getFailState();
					}
				}
			} else if (rewardTotal.signum() != 0) {
				if (throwExceptions)
					throw new InvalidTransactionException(
							"Non-minting beacon carries reward outputs");
				return SolidityState.getFailState();
			}
			if (activeStake.signum() > 0 && rewardTotal.compareTo(activeStake) > 0) {
				if (throwExceptions)
					throw new InvalidTransactionException("Epoch reward exceeds the total active stake");
				return SolidityState.getFailState();
			}
		} catch (BlockStoreException e) {
			// A DB error during mint validation is fail-closed: reject.
			if (throwExceptions)
				throw e;
			return SolidityState.getFailState();
		} catch (InvalidTransactionException e) {
			if (throwExceptions)
				throw e;
			return SolidityState.getFailState();
		} catch (Exception e) {
			if (throwExceptions)
				throw new InvalidTransactionException("Epoch reward cannot be recomputed", e);
			return SolidityState.getFailState();
		}

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
		case BLOCKTYPE_STAKE: {
			SolidityState stakeState = checkStakeDepositSolidity(block, throwExceptions, null);
			if (!(stakeState.getState() == State.Success)) {
				return stakeState;
			}
			break;
		}
		case BLOCKTYPE_SLASHING: {
			SolidityState slashState = checkSlashingSolidity(block, throwExceptions);
			if (!(slashState.getState() == State.Success)) {
				return slashState;
			}
			break;
		}
		case BLOCKTYPE_EXIT: {
			SolidityState exitState = checkExitSolidity(block, throwExceptions);
			if (!(exitState.getState() == State.Success)) {
				return exitState;
			}
			break;
		}
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

		if (transactions.isEmpty()) {
			if (throwExceptions)
				throw new IncorrectTransactionCountException();
			return SolidityState.getFailState();
		}

		// No output creation in the RewardInfo transaction itself
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

		// Reward-output transactions (epoch reward block) must be coinbase-like:
		// no inputs, no data, only freshly minted outputs. The SlotData tx is exempt.
		for (int i = 1; i < transactions.size(); i++) {
			Transaction rewardTx = transactions.get(i);
			if ("SlotData".equals(rewardTx.getDataClassName())) {
				continue;
			}
			if (!rewardTx.getInputs().isEmpty() || rewardTx.getData() != null || rewardTx.getOutputs().isEmpty()) {
				if (throwExceptions)
					throw new IncorrectTransactionCountException();
				return SolidityState.getFailState();
			}
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
			if (cutoffHeight < 0) {
				// Predecessor chain not fully synced — the cutoff cannot be
				// verified. Defer (never reject): the check re-runs once the
				// chain is synced. Previously this NPE'd and stalled the chain.
				return SolidityState.fromPrevReward(prevRewardHash, true);
			}

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
