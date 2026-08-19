package net.bigtangle.server.service.base;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.BlockType;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
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

	public RewardBuilderResult calcRewardInfo(BlockWrap prevTrunk, BlockWrap prevBranch,
			Sha256Hash prevRewardHash, long currentTime, BlockStoreInterface store) throws BlockStoreException {

		// Read previous reward block's data
		long prevChainLength = store.getRewardChainLength(prevRewardHash);

		long cutoffheight = getRewardCutoffHeight(prevRewardHash, store);
		List<BlockType> ordertypes = getListedBlockOfType();

		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);

		Set<BlockWrap> blocks = new HashSet<>();
		serviceBase.dagBlockHashesFrom(blocks, prevBranch, cutoffheight, prevChainLength, ordertypes, true,
				true, store);
		serviceBase.dagBlockHashesFrom(blocks, prevTrunk, cutoffheight, prevChainLength, ordertypes, true, true,
				store);
		// Keep the reward block set identical to the proposer's prototype
		// (SlotService.calcNewBlockPrototype): reference every eligible
		// unconfirmed block above the cutoff, not only the tip paths.
		serviceBase.addAllUnconfirmedBlocks(blocks, cutoffheight, ordertypes, true, store);
		return calcRewardInfo(false, prevTrunk, prevBranch, prevRewardHash, currentTime,
				serviceBase.getHashSet(blocks), store);
	}

	private List<BlockType> getListedBlockOfType() {
		List<BlockType> ordertypes = new ArrayList<>();

		ordertypes.add(BlockType.BLOCKTYPE_INITIAL);
		ordertypes.add(BlockType.BLOCKTYPE_TRANSFER);
		ordertypes.add(BlockType.BLOCKTYPE_TOKEN_CREATION);
		ordertypes.add(BlockType.BLOCKTYPE_FILE);
		ordertypes.add(BlockType.BLOCKTYPE_USERDATA);
		// Reward can not be as Referenced ordertypes.add(BlockType.BLOCKTYPE_BEACON);
		ordertypes.add(BlockType.BLOCKTYPE_GOVERNANCE);
		ordertypes.add(BlockType.BLOCKTYPE_CROSSTANGLE);
		ordertypes.add(BlockType.BLOCKTYPE_STAKE);
		ordertypes.add(BlockType.BLOCKTYPE_SLASHING);
		ordertypes.add(BlockType.BLOCKTYPE_EXIT);
		ordertypes.add(BlockType.BLOCKTYPE_ORDER_OPEN);
		ordertypes.add(BlockType.BLOCKTYPE_ORDER_CANCEL);
		ordertypes.add(BlockType.BLOCKTYPE_CONTRACT_EVENT);
		ordertypes.add(BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL);
		ordertypes.add(BlockType.BLOCKTYPE_EVM_DEPLOY);
		ordertypes.add(BlockType.BLOCKTYPE_EVM_CALL);

		return ordertypes;
	}

}
