package net.bigtangle.server.service.base.handler;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Narrow interface exposing the few base methods that the (Layer-1) contract
 * engine needs from the {@code ServiceBase} hierarchy, without forcing it to
 * <em>extend</em> that hierarchy.
 *
 * <p>This breaks what would otherwise be a circular module dependency:
 * {@code ServiceContract} lived in {@code bigtangle-servercore} because it
 * {@code extends ServiceBaseConnect}, while {@code ServiceBaseConnect} called
 * {@code new ServiceContract()}. By moving the contract logic into
 * {@code layer1-servercore} and having it depend only on this interface
 * (implemented by {@code ServiceBaseConnect}), {@code bigtangle-servercore}
 * no longer references {@code ServiceContract} at all. See
 * {@code LAYERING-PLAN.md} (Approach A).
 *
 * <p>The three methods mirror the existing protected/public signatures on
 * {@code ServiceBase}/{@code ServiceBaseConnect}.
 */
public interface ContractConnectSupport {

	/** Fetch a block by hash. Mirrors {@code ServiceBase.getBlock}. */
	Block getBlock(Sha256Hash blockhash, BlockStoreInterface store) throws BlockStoreException;

	/** Connect the generic UTXOs of a block. Mirrors {@code ServiceBaseConnect.connectUTXOs}. */
	void connectUTXOs(Block block, BlockStoreInterface blockStore) throws BlockStoreException;

	/**
	 * Connect the type-specific UTXOs/state of a block (token, order, contract,
	 * ...). Mirrors {@code ServiceBaseConnect.connectTypeSpecificUTXOs}.
	 */
	void connectTypeSpecificUTXOs(Block block, BlockStoreInterface blockStore) throws BlockStoreException;
}
