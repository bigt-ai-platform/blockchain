package net.bigtangle.evm;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import net.bigtangle.core.Sha256Hash;

/**
 * Applies an ordered batch of {@link EVMTx} to a {@link WorldState} and
 * produces the resulting state root plus per-transaction receipts. The output
 * is fully deterministic: same transactions, same prior state and same block
 * context always yield the same state root — the consensus invariant.
 *
 * <p>Semantics (simplified): intrinsic gas is derived from the payload, the
 * sender pays {@code value + gasPrice*gasLimit} up front, the nonce is
 * incremented, and on failure all execution side effects (including the value
 * transfer) are rolled back while the fee and nonce increment persist.
 */
public final class EVMTxProcessor {

	private static final long GTXCOST = 21000;
	private static final long GTXCREATE = 32000;
	private static final long GTXDATAZERO = 4;
	private static final long GTXDATANONZERO = 16;
	private static final long GCODEDEPOSIT = 200;
	private static final int MAX_CODE_DEPOSIT = 24576;

	private final EVMInterpreter interpreter;
	private final BlockContext blockContext;

	public EVMTxProcessor(EVMInterpreter interpreter, BlockContext blockContext) {
		this.interpreter = interpreter;
		this.blockContext = blockContext;
	}

	public EVMBatchResult process(List<EVMTx> txs, WorldState worldState) {
		WorldState state = worldState.copy();
		List<EVMTxReceipt> receipts = new ArrayList<>();
		long cumulativeGas = 0;
		for (EVMTx tx : txs) {
			try {
				EVMTxReceipt receipt = apply(tx, state, cumulativeGas);
				cumulativeGas += receipt.getGasUsed();
				receipts.add(receipt);
			} catch (EVMInvalidTxException e) {
				// Rejected at pool level: no state change, recorded as a failed
				// receipt so the batch stays total and deterministic.
				receipts.add(new EVMTxReceipt(EVMTxReceipt.STATUS_FAILURE, 0, cumulativeGas, List.of(), null,
						new byte[0], tx.getSender(), tx.getTo()));
			}
		}
		Sha256Hash root = EVMStateRoot.compute(state);
		return new EVMBatchResult(state, root, receipts, cumulativeGas);
	}

	private EVMTxReceipt apply(EVMTx tx, WorldState state, long cumulativeGas) {
		EVMAccount sender = state.getOrCreateAccount(tx.getSender());
		if (sender.getNonce() != tx.getNonce()) {
			throw new EVMInvalidTxException(
					"nonce mismatch: expected " + sender.getNonce() + " got " + tx.getNonce());
		}
		long intrinsic = GTXCOST + (tx.getTo() == null ? GTXCREATE : 0) + dataCost(tx.getData());
		if (tx.getGasLimit() < intrinsic) {
			throw new EVMInvalidTxException("intrinsic gas too low: " + tx.getGasLimit() + " < " + intrinsic);
		}
		BigInteger fee = tx.getGasPrice().toBigInteger().multiply(BigInteger.valueOf(tx.getGasLimit()));
		BigInteger upfront = tx.getValue().add(fee);
		if (sender.getBalance().compareTo(upfront) < 0) {
			throw new EVMInvalidTxException("insufficient funds");
		}
		// Only the fee is taken up front; the value moves via the execution's
		// transfer so it is not double-deducted.
		sender.setBalance(sender.getBalance().subtract(fee));
		long oldNonce = sender.getNonce();
		sender.setNonce(oldNonce + 1);

		long gas = tx.getGasLimit() - intrinsic;
		// Snapshot after fee + nonce so those persist on failure (Ethereum semantics).
		WorldState snapshot = state.copy();

		Message message;
		Address contractAddress = null;
		if (tx.getTo() == null) {
			contractAddress = Rlp.createAddress(tx.getSender(), oldNonce);
			state.transfer(tx.getSender(), contractAddress, tx.getValue());
			state.getOrCreateAccount(contractAddress);
			message = Message.create(tx.getSender(), tx.getSender(), contractAddress, tx.getData(), gas,
					tx.getGasPrice());
		} else {
			state.transfer(tx.getSender(), tx.getTo(), tx.getValue());
			message = Message.call(tx.getSender(), tx.getSender(), tx.getTo(), tx.getValue(), tx.getData(), gas,
					tx.getGasPrice());
		}

		EVMExecutionResult result = interpreter.execute(message, state, blockContext);

		if (tx.getTo() == null && result.isSuccess()) {
			long deposit = (long) GCODEDEPOSIT * result.getReturnData().length;
			if (result.getReturnData().length > MAX_CODE_DEPOSIT || deposit > result.getGasRemaining()) {
				state.replaceFrom(snapshot);
				result = new EVMExecutionResult(false, 0, new byte[0], result.getLogs());
			} else {
				state.setCode(contractAddress, result.getReturnData());
				long remaining = result.getGasRemaining() - deposit;
				result = new EVMExecutionResult(true, remaining, result.getReturnData(), result.getLogs());
			}
		}

		boolean success = result.isSuccess();
		long gasUsed = tx.getGasLimit() - result.getGasRemaining();
		if (!success) {
			state.replaceFrom(snapshot);
		}
		BigInteger refund = tx.getGasPrice().toBigInteger().multiply(BigInteger.valueOf(result.getGasRemaining()));
		state.getOrCreateAccount(tx.getSender()).setBalance(state.getBalance(tx.getSender()).add(refund));

		return new EVMTxReceipt(success ? EVMTxReceipt.STATUS_SUCCESS : EVMTxReceipt.STATUS_FAILURE, gasUsed,
				cumulativeGas + gasUsed, result.getLogs(), contractAddress, result.getReturnData(), tx.getSender(),
				tx.getTo());
	}

	private static long dataCost(byte[] data) {
		long cost = 0;
		for (byte b : data) {
			cost += b == 0 ? GTXDATAZERO : GTXDATANONZERO;
		}
		return cost;
	}
}
