package net.bigtangle.evm;

import java.util.ArrayList;
import java.util.List;

import net.bigtangle.core.Sha256Hash;

/**
 * The outcome of applying an ordered batch of EVM transactions: the resulting
 * world state, its deterministic state root and one receipt per transaction.
 */
public final class EVMBatchResult {

	private final WorldState worldState;
	private final Sha256Hash stateRoot;
	private final List<EVMTxReceipt> receipts;
	private final long totalGasUsed;

	public EVMBatchResult(WorldState worldState, Sha256Hash stateRoot, List<EVMTxReceipt> receipts,
			long totalGasUsed) {
		this.worldState = worldState;
		this.stateRoot = stateRoot;
		this.receipts = new ArrayList<>(receipts);
		this.totalGasUsed = totalGasUsed;
	}

	public WorldState getWorldState() {
		return worldState;
	}

	public Sha256Hash getStateRoot() {
		return stateRoot;
	}

	public List<EVMTxReceipt> getReceipts() {
		return receipts;
	}

	public long getTotalGasUsed() {
		return totalGasUsed;
	}
}
