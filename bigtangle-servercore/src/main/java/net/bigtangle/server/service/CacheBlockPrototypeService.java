package net.bigtangle.server.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class CacheBlockPrototypeService {
	  private static final Logger log  =
	  LoggerFactory.getLogger(CacheBlockPrototypeService.class);

	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	protected ServerConfiguration serverConfiguration;
	@Autowired
	protected GhostService ghostService;

	/**
	 * The next block template: a block approving the two current DAG tips
	 * selected by the PoS GHOST fork choice. Successors attach transactions to
	 * this prototype; the beacon proposer references the same tip pair so the
	 * template inherits the current fork-choice head.
	 */
	public Block getBlockPrototype(BlockStoreInterface store) throws BlockStoreException, NoBlockException {
		try {
			List<Sha256Hash> tips = ghostService.getTwoTips(store);
			if (tips == null || tips.isEmpty()) {
				throw new NoBlockException();
			}
			Block trunk = store.get(tips.get(0));
			Block branch = tips.size() > 1 ? store.get(tips.get(1)) : trunk;
			if (trunk == null) {
				throw new NoBlockException();
			}
			return Block.createBlock(networkParameters, trunk, branch);
		} catch (NoBlockException e) {
			throw e;
		} catch (Exception e) {
			log.debug("getBlockPrototype failed", e);
			throw new NoBlockException();
		}
	}

}