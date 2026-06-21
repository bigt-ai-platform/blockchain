package net.bigtangle.server.service.base.handler;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Narrow interface extending {@link ContractConnectSupport} with the additional
 * {@code getBlockWrap} method needed by the Layer-1 order matching engine
 * (formerly {@code ServiceOrderExecution}).
 *
 * <p>{@code ServiceBaseConnect} already defines all four methods ({@code getBlock},
 * {@code getBlockWrap}, {@code connectUTXOs}, {@code connectTypeSpecificUTXOs}),
 * so it naturally satisfies this interface without any new code.
 */
public interface OrderMatchingSupport extends ContractConnectSupport {

	/** Fetch a block + evaluation + MCMC wrapped. Mirrors {@code ServiceBase.getBlockWrap}. */
	BlockWrap getBlockWrap(Sha256Hash blockhash, BlockStoreInterface store) throws BlockStoreException;
}
