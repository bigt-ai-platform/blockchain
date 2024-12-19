package net.bigtangle.server.service.base;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;

/*
 * ServiceBaseReward can create new Reward with the referenced blocks
 */
public class ServiceBaseReward extends ServiceVerifyReward {

	public ServiceBaseReward(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService, ObjectMapper jsonmapper) {
		super(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);

	}

	public RewardBuilderResult calcRewardInfo(boolean contractExecute, BlockWrap prevTrunk, BlockWrap prevBranch,
			Sha256Hash prevRewardHash, long currentTime, BlockStoreInterface store) throws BlockStoreException {

		// Read previous reward block's data
		long prevChainLength = store.getRewardChainLength(prevRewardHash);

		long cutoffheight = getRewardCutoffHeight(prevRewardHash, store);
		List<Block.Type> ordertypes = getListedBlockOfType(contractExecute);

		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);

		Set<BlockWrap> blocks = new HashSet<>();
		serviceBase.addReferencedBlockHashesTo(blocks, prevBranch, cutoffheight, prevChainLength, ordertypes, true,
				store);
		serviceBase.addReferencedBlockHashesTo(blocks, prevTrunk, cutoffheight, prevChainLength, ordertypes, true,
				store);
		Comparator<BlockWrap> comparator = Comparator.comparingLong((BlockWrap b) -> b.getBlock().getHeight())
				.thenComparing((BlockWrap b) -> b.getBlock().getHash());
		TreeSet<BlockWrap> storedBlockHashes = new TreeSet<>(comparator);
		storedBlockHashes.addAll(blocks);
		serviceBase.removeMilestoneConflicts(storedBlockHashes, store);

		Set<BlockWrap> collected = new HashSet<>();
		Set<BlockWrap> unconfirms = new HashSet<>();
		// chained add check conflict inside
		collectExecutionChained(store, blocks, collected, unconfirms);
		// do unconfirm, this is build up process consistent, not verify
		unconfirmBlocksSorted(store, unconfirms, new HashSet<>());

		return calcRewardInfo(contractExecute, prevTrunk, prevBranch, prevRewardHash, currentTime,
				serviceBase.getHashSet(collected), store);
	}

	private List<Block.Type> getListedBlockOfType(boolean contractExecute) {
		List<Block.Type> ordertypes = new ArrayList<>();

		ordertypes.add(Block.Type.BLOCKTYPE_INITIAL);
		ordertypes.add(Block.Type.BLOCKTYPE_TRANSFER);
		ordertypes.add(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		ordertypes.add(Block.Type.BLOCKTYPE_FILE);
		ordertypes.add(Block.Type.BLOCKTYPE_USERDATA);
		// Reward can not be as Referenced ordertypes.add(Block.Type.BLOCKTYPE_REWARD);
		ordertypes.add(Block.Type.BLOCKTYPE_GOVERNANCE);
		ordertypes.add(Block.Type.BLOCKTYPE_CROSSTANGLE);

		if (contractExecute) {
			// exclude order open , cancel
			ordertypes.add(Block.Type.BLOCKTYPE_ORDER_EXECUTE);
			ordertypes.add(Block.Type.BLOCKTYPE_CONTRACT_EXECUTE);
		} else {
			ordertypes.add(Block.Type.BLOCKTYPE_ORDER_OPEN);
			ordertypes.add(Block.Type.BLOCKTYPE_ORDER_CANCEL);
			ordertypes.add(Block.Type.BLOCKTYPE_CONTRACT_EVENT);
			ordertypes.add(Block.Type.BLOCKTYPE_CONTRACTEVENT_CANCEL);

		}
		return ordertypes;
	}

}
