package net.bigtangle.evm;

/**
 * Signals a transaction that cannot be included in a block (nonce mismatch or
 * insufficient balance for the upfront value + gas). Such transactions are
 * rejected by the mempool / block builder before execution and therefore never
 * produce a receipt.
 */
public final class EVMInvalidTxException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public EVMInvalidTxException(String message) {
		super(message);
	}
}
