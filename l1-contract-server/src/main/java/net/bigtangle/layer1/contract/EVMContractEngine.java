package net.bigtangle.layer1.contract;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.EVMTransactionInfo;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.evm.BlockContext;
import net.bigtangle.evm.EVMAddressUtil;
import net.bigtangle.evm.EVMBatchResult;
import net.bigtangle.evm.EVMStateCodec;
import net.bigtangle.evm.EVMTx;
import net.bigtangle.evm.EVMTxProcessor;
import net.bigtangle.evm.EVMTxReceipt;
import net.bigtangle.evm.MinimalEVMInterpreter;
import net.bigtangle.evm.Word;
import net.bigtangle.evm.WorldState;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.service.base.handler.ContractConnectSupport;
import net.bigtangle.server.service.base.handler.ContractExecutor;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Layer-1 EVM contract engine. Implements {@link ContractExecutor} and executes
 * the EVM transactions referenced by a beacon block against the EVM world state
 * derived from the previous contract result, producing a deterministic state
 * root and the UTXO payouts for EVM withdrawals.
 *
 * <p>The world state is persisted inside the {@link ContractExecutionResult}
 * {@code extraData} (see {@link EVMStateCodec}) so every node can re-derive and
 * compare it — that root is the consensus output.
 */
public class EVMContractEngine implements ContractExecutor {

	public static final String CLASSNAME = "net.bigtangle.l1.evm.EVMContract";
	private static final long DEFAULT_BLOCK_GAS_LIMIT = 30_000_000L;

	@Override
	public ContractExecutionResult executeContract(ContractConnectSupport support, NetworkParameters networkParameters,
			Block block, BlockStoreInterface blockStore, String contractid,
			Contractresult prevHash, Set<Sha256Hash> referencedblocks) throws BlockStoreException {
		Token contract = blockStore.getTokenID(contractid).get(0);
		return executeContract(support, networkParameters, block, blockStore, contract, prevHash, referencedblocks);
	}

	public ContractExecutionResult executeContract(ContractConnectSupport support, NetworkParameters networkParameters,
			Block block, BlockStoreInterface blockStore, Token contract,
			Contractresult prevHash, Set<Sha256Hash> referencedblocks) throws BlockStoreException {

		// 1. load the previous world state (or empty for the first execution)
		WorldState worldState = loadPreviousState(prevHash);

		// 2. collect this contract's EVM blocks in canonical order
		List<Sha256Hash> ordered = new ArrayList<>(referencedblocks);
		Collections.sort(ordered);

		List<EVMTx> txs = new ArrayList<>();
		List<Sha256Hash> txBlocks = new ArrayList<>();
		List<Withdrawal> withdrawals = new ArrayList<>();
		List<Sha256Hash> processed = new ArrayList<>();

		for (Sha256Hash h : ordered) {
			Block b = support.getBlock(h, blockStore);
			if (b.getBlockType() != BlockType.BLOCKTYPE_EVM_DEPLOY
					&& b.getBlockType() != BlockType.BLOCKTYPE_EVM_CALL) {
				continue;
			}
			EVMTransactionInfo info = new EVMTransactionInfo().parseChecked(b.getTransactions().get(0).getData());
			if (!contract.getTokenid().equals(info.getContractTokenid())) {
				continue;
			}
			processed.add(h);
			net.bigtangle.evm.Address sender = EVMAddressUtil.evmAddressFromBase58(info.getFromAddress());

			if (info.getValue() != null && info.getValue().signum() > 0) {
				worldState.addBalance(sender, info.getValue());
			}

			if (info.isDeploy() || info.isCall()) {
				net.bigtangle.evm.Address to = info.getTo() == null ? null : net.bigtangle.evm.Address.fromHex(info.getTo());
				txs.add(new EVMTx(sender, to,
						info.getValue() == null ? BigInteger.ZERO : info.getValue(),
						info.getData() == null ? new byte[0] : info.getData(),
						info.getGasLimit(), Word.of(info.getGasPrice()), info.getNonce()));
				txBlocks.add(h);
			} else if (info.isWithdraw()) {
				withdrawals.add(new Withdrawal(sender, info.getValue(), info.getTokenid(), info.getFromAddress()));
			}
			// pure deposit: already credited above
		}

		if (processed.isEmpty()) {
			return null;
		}

		// 3. execute the EVM batch
		BlockContext blockContext = new BlockContext(Word.ZERO, block.getTimeSeconds(),
				block.getHeight(), Word.ZERO, DEFAULT_BLOCK_GAS_LIMIT, 0,
				Word.ZERO, h -> Word.ZERO);
		EVMTxProcessor processor = new EVMTxProcessor(new MinimalEVMInterpreter(), blockContext);
		net.bigtangle.evm.EVMBatchResult batch = processor.process(txs, worldState);

		// 4. withdrawals: deduct EVM balance and emit UTXO payouts
		Transaction outputTx = new Transaction(networkParameters);
		boolean hasPayout = false;
		for (Withdrawal w : withdrawals) {
			if (batch.getWorldState().subtractBalance(w.evmAddress, w.amount)) {
				String tokenid = w.tokenid != null ? w.tokenid : contract.getTokenid();
				outputTx.addOutput(new Coin(w.amount, tokenid),
						Address.fromBase58(networkParameters, w.base58Address));
				hasPayout = true;
			}
		}
		if (hasPayout) {
			TransactionInput input = TransactionInput.fromScriptBytes(networkParameters, outputTx,
					Script.createInputScript(block.getPrevBlockHash().getBytes(),
							block.getPrevBranchBlockHash().getBytes()));
			outputTx.addInput(input);
			outputTx.setMemo(new net.bigtangle.core.MemoInfo("evmWithdraw"));
		}

		// 5. persist receipts (deterministic side effect)
		for (int i = 0; i < txs.size(); i++) {
			if (i < batch.getReceipts().size()) {
				blockStore.insertEVMReceipt(txBlocks.get(i), contract.getTokenid(),
						batch.getReceipts().get(i).toByteArray());
			}
		}

		// 6. build the deterministic result
		Sha256Hash prevBlockHash = prevHash == null ? Sha256Hash.ZERO_HASH : prevHash.getBlockHash();
		long chainlength = (prevHash == null ? 0 : prevHash.getChainlength()) + 1;
		ContractExecutionResult result = new ContractExecutionResult(contract.getTokenid(),
				outputTx.getHash(), outputTx, prevBlockHash,
				new java.util.HashSet<>(), new java.util.HashSet<>(), block.getTimeSeconds(),
				new java.util.HashSet<>(), new java.util.HashSet<>(processed), chainlength);
		result.setExtraData(EVMStateCodec.serialize(batch.getWorldState()));
		return result;
	}

	private WorldState loadPreviousState(Contractresult prevHash) {
		if (prevHash == null || Sha256Hash.ZERO_HASH.equals(prevHash.getBlockHash())
				|| prevHash.getContractExecutionResult() == null) {
			return new WorldState();
		}
		try {
			ContractExecutionResult prev = new ContractExecutionResult().parseChecked(prevHash.getContractExecutionResult());
			if (prev.getExtraData() != null) {
				return EVMStateCodec.deserialize(prev.getExtraData());
			}
		} catch (RuntimeException e) {
			// fall through to a fresh state; a mismatched root will be caught by
			// the state-root comparison on every node
		}
		return new WorldState();
	}

	private static final class Withdrawal {
		final net.bigtangle.evm.Address evmAddress;
		final BigInteger amount;
		final String tokenid;
		final String base58Address;

		Withdrawal(net.bigtangle.evm.Address evmAddress, BigInteger amount, String tokenid, String base58Address) {
			this.evmAddress = evmAddress;
			this.amount = amount == null ? BigInteger.ZERO : amount;
			this.tokenid = tokenid;
			this.base58Address = base58Address;
		}
	}
}
