package net.bigtangle.evm;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of running a single EVM message. On success {@code gasRemaining}
 * is the unused gas returned to the caller; on an exceptional halt it is zero.
 * On {@code REVERT} the call fails but the unused gas is still returned, and
 * {@code returnData} holds the revert reason.
 */
public final class EVMExecutionResult {

	private final boolean success;
	private final long gasRemaining;
	private final byte[] returnData;
	private final List<EVMLog> logs;

	public EVMExecutionResult(boolean success, long gasRemaining, byte[] returnData, List<EVMLog> logs) {
		this.success = success;
		this.gasRemaining = gasRemaining;
		this.returnData = returnData == null ? new byte[0] : returnData.clone();
		this.logs = new ArrayList<>(logs);
	}

	public boolean isSuccess() {
		return success;
	}

	public long getGasRemaining() {
		return gasRemaining;
	}

	public byte[] getReturnData() {
		return returnData;
	}

	public List<EVMLog> getLogs() {
		return logs;
	}
}
