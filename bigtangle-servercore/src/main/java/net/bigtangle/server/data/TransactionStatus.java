package net.bigtangle.server.data;

/**
 * Lifecycle status of a user transaction, tracked from mempool entry to
 * confirmation as chain history (and back to the mempool if its block is
 * dropped by a reorg/conflict).
 */
public enum TransactionStatus {

	/** In the mempool, not yet in any block. */
	MEMPOOL,

	/** Drained from the mempool into a transient batch block. */
	BATCHED,

	/** Placed in a block of the DAG. */
	IN_BLOCK,

	/** Block became fully solid (solid=2), eligible for MCMC. */
	SOLID,

	/** Confirmed by a beacon/reward block at a reward chainlength (chain history). */
	CONFIRMED,

	/** Block conflicted with the chain or was unconfirmed by a reorg. */
	DROPPED
}
