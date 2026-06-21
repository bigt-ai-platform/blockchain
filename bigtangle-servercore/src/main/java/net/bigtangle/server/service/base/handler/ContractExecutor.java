package net.bigtangle.server.service.base.handler;

import java.util.Set;

import net.bigtangle.core.Block;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Service Provider Interface for executing a contract. Implemented by the
 * Layer-1 module ({@code ContractEngine}) and looked up by
 * {@code bigtangle-servercore} via {@link ContractExecutorRegistry} so that
 * servercore never imports {@code ServiceContract} / {@code layer1-servercore}
 * directly. This is what dissolves the circular dependency described in
 * {@link ContractConnectSupport}.
 *
 * <p>Mirrors {@code ServiceContract.executeContract}.
 */
public interface ContractExecutor {

	/**
	 * Re-run the contract referenced by {@code contractid} against the given
	 * referenced blocks and produce the deterministic execution result.
	 *
	 * @param support         access to the base hierarchy methods the engine
	 *                        needs ({@code getBlock}, {@code connectUTXOs},
	 *                        {@code connectTypeSpecificUTXOs})
	 * @param networkParameters the chain parameters (for tx/address construction)
	 * @param block           the contract-execute block being processed
	 * @param blockStore      the store
	 * @param contractid      the contract token id
	 * @param prevHash        the previous contract result in the chain
	 * @param referencedblocks the blocks approved by this execute block
	 */
	ContractExecutionResult executeContract(ContractConnectSupport support, NetworkParameters networkParameters,
			Block block, BlockStoreInterface blockStore, String contractid, Contractresult prevHash,
			Set<Sha256Hash> referencedblocks) throws BlockStoreException;
}
