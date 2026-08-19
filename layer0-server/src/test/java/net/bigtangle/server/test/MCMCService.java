package net.bigtangle.server.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.store.BlockStoreInterface;

/**
 * Test stand-in for the removed MCMC consensus machinery. On PoS there is no
 * weight/depth/rating recomputation, so {@code update} is a no-op; the block
 * template comes from the GHOST fork choice instead of a TipsQueue, so
 * {@code calcNewBlockPrototype} is a no-op too.
 */
@Service
public class MCMCService {

	private static final Logger log = LoggerFactory.getLogger(MCMCService.class);

	public void update(BlockStoreInterface store) {
	}

	public void calcNewBlockPrototype(BlockStoreInterface store) {
	}
}