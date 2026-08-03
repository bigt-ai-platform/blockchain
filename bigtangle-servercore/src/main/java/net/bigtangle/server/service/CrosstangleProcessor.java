package net.bigtangle.server.service;

import net.bigtangle.core.Block;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Callback invoked for CROSSTANGLE blocks (cross-chain bridge messages).
 *
 * <p>Implemented by the bridge module so the core server can notify it without
 * depending on it. Two events are relevant:
 * <ul>
 *   <li>a CROSSTANGLE block is saved locally or received from a peer (batch),
 *       where a layer anchor is validated and recorded;</li>
 *   <li>a CROSSTANGLE block is confirmed or unconfirmed (reorg) on this chain,
 *       where an anchor's finality and any peg-out is (un)settled.</li>
 * </ul>
 */
public interface CrosstangleProcessor {

    void onCrosstangleBlockSaved(Block block, BlockStoreInterface store) throws Exception;

    void onCrosstangleBlockConfirmed(Block block, boolean confirmed, BlockStoreInterface store) throws Exception;
}
