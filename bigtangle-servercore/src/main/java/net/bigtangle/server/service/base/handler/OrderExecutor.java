package net.bigtangle.server.service.base.handler;

import java.util.Set;

import net.bigtangle.core.Block;
import net.bigtangle.core.OrderExecutionResult;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.data.Orderresult;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Service Provider Interface for executing order matching. Implemented by the
 * Layer-1 module ({@code OrderMatchingEngine}) and looked up by
 * {@code bigtangle-servercore} via {@link OrderExecutorRegistry}.
 *
 * <p>Mirrors {@code ServiceOrderExecution.orderMatching}.
 */
public interface OrderExecutor {

	/**
	 * Deterministically execute order matching for the given block.
	 *
	 * @param support          access to {@code getBlockWrap}, {@code connectUTXOs},
	 *                         {@code connectTypeSpecificUTXOs}
	 * @param networkParameters the chain parameters (for tx/address construction)
	 * @param block            the order-execute block being processed
	 * @param prev             the previous order result in the chain
	 * @param collectedBlocks  the blocks approved by this execute block
	 * @param blockStore       the store
	 */
	OrderExecutionResult executeOrderMatching(OrderMatchingSupport support, NetworkParameters networkParameters,
			Block block, Orderresult prev, Set<Sha256Hash> collectedBlocks,
			BlockStoreInterface blockStore) throws BlockStoreException;
}
