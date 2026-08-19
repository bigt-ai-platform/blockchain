package net.bigtangle.server.test;

import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.GhostService;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Test stand-in for the PoS tip selection. Mirrors the production
 * {@link GhostService#getTwoTips} reward-chain fork choice, falling back to the
 * confirmed reward head when GHOST has no children yet.
 */
@Service
public class TipsService {

	private static final Logger log = LoggerFactory.getLogger(TipsService.class);

	@Autowired
	protected CacheBlockService cacheBlockService;
	@Autowired
	protected GhostService ghostService;

	public Pair<BlockWrap, BlockWrap> getValidatedBlockPair(BlockStoreInterface store) throws BlockStoreException {
		return getValidatedBlockPair(cacheBlockService.getMaxConfirmedReward(store), new HashSet<>(), store);
	}

	public Pair<BlockWrap, BlockWrap> getValidatedBlockPair(TXReward maxConfirmedReward,
			HashSet<BlockWrap> currentApprovedNonChainlengthBlocks, BlockStoreInterface store)
			throws BlockStoreException {
		try {
			List<Sha256Hash> tips = ghostService.getTwoTips(store);
			if (tips != null && !tips.isEmpty()) {
				BlockWrap t1 = store.getBlockWrap(tips.get(0));
				BlockWrap t2 = tips.size() > 1 ? store.getBlockWrap(tips.get(1)) : t1;
				if (t1 != null) {
					return Pair.of(t1, t2 != null ? t2 : t1);
				}
			}
		} catch (Exception e) {
			log.debug("ghost tips unavailable, falling back to reward head: {}", e.getMessage());
		}
		BlockWrap head = store.getBlockWrap(maxConfirmedReward.getBlockHash());
		return Pair.of(head, head);
	}
}