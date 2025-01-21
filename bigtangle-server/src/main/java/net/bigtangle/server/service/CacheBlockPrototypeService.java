package net.bigtangle.server.service;

import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.NoBlockException;
import net.bigtangle.core.exception.ProtocolException;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class CacheBlockPrototypeService {
	// private static final Logger logger =
	// LoggerFactory.getLogger(CacheBlockPrototypeService.class);

	@Autowired
	protected TipsService tipService;
	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	protected ServerConfiguration serverConfiguration;

	@Cacheable(value = "BlockPrototype", key = "#store.getParams.getId")
	private byte[] getBlockPrototypeByte(BlockStoreInterface store) throws BlockStoreException, NoBlockException {
		// logger.debug("blockService.getNewBlockPrototype(store " ) ;
		return calcNewBlockPrototype(store).unsafeBitcoinSerialize();

	}

	@CacheEvict(value = "BlockPrototype", allEntries = true)
	public void evictBlockPrototypeByte() {
		// logger.debug("evictBlockPrototypeByte" ) ;
	}

	public Block getBlockPrototype(BlockStoreInterface store)
			throws ProtocolException, BlockStoreException, NoBlockException {
		return networkParameters.getDefaultSerializer().makeBlock(getBlockPrototypeByte(store));
	}

	private Block calcNewBlockPrototype(BlockStoreInterface store) throws BlockStoreException {
		Pair<BlockWrap, BlockWrap> tipsToApprove = tipService.getValidatedBlockPair(store);
		Block b = Block.createBlock(networkParameters, tipsToApprove.getLeft().getBlock(),
				tipsToApprove.getRight().getBlock());
		b.setMinerAddress(Address.fromBase58(networkParameters, serverConfiguration.getMineraddress()).getHash160());

		return b;
	}

}