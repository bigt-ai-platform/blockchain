package net.bigtangle.server.service;

import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.google.common.base.Stopwatch;

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
	  private static final Logger log  =
	  LoggerFactory.getLogger(CacheBlockPrototypeService.class);

	@Autowired
	protected TipsService tipService;
	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	protected ServerConfiguration serverConfiguration;

	@Cacheable(value = "BlockPrototype", key = "#store.getParams.getId", sync=true)
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

	private synchronized  Block calcNewBlockPrototype(BlockStoreInterface store) throws BlockStoreException {
	 //	log.debug("calcNewBlockPrototype start" ) ;
		   Stopwatch watch = Stopwatch.createStarted();
		Pair<BlockWrap, BlockWrap> tipsToApprove = tipService.getValidatedBlockPair(store);
		Block b = Block.createBlock(networkParameters, tipsToApprove.getLeft().getBlock(),
				tipsToApprove.getRight().getBlock());
		b.setMinerAddress(Address.fromBase58(networkParameters, serverConfiguration.getMineraddress()).getHash160());
		if(watch.elapsed(TimeUnit.MILLISECONDS)>2000)
		log.debug("calcNewBlockPrototype finish MILLISECONDS {} ", watch.elapsed(TimeUnit.MILLISECONDS) ) ;
		return b;
	}

}