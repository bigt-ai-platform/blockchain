package net.bigtangle.evm;

/**
 * Executes an EVM message against a mutable {@link WorldState}. Implementations
 * must be deterministic: given the same message, world state and block context
 * they must return the same result and produce the same state — every node must
 * derive the same state root for consensus.
 *
 * <p>Value transfer for the top-level message is the caller's responsibility
 * ({@link EVMTxProcessor} or the call site); opcode-level transfers are handled
 * by the interpreter itself.
 */
public interface EVMInterpreter {

	/**
	 * Runs the message. The world state is mutated in place.
	 *
	 * @param message the message to execute
	 * @param worldState the world state, mutated on success (and on failure for
	 *        call-level side effects, which callers roll back via snapshots)
	 * @param blockContext the block context
	 * @return the execution result
	 */
	EVMExecutionResult execute(Message message, WorldState worldState, BlockContext blockContext);
}
