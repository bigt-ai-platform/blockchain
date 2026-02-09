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
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.exception.ProtocolException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class CacheBlockPrototypeService {
	  private static final Logger log  =
	  LoggerFactory.getLogger(CacheBlockPrototypeService.class);

	 
	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	protected ServerConfiguration serverConfiguration;

	 
	public Block getBlockPrototype(BlockStoreInterface store) throws BlockStoreException, NoBlockException {
		// logger.debug("blockService.getNewBlockPrototype(store " ) ;
		net.bigtangle.server.data.TipsQueue tipsQueue = store.getTipsQueue();
		 if(tipsQueue == null) {
			 throw new NoBlockException( );
		 }	
		return networkParameters.getDefaultSerializer().makeBlock(tipsQueue.getBlock());
  
	}
 

}