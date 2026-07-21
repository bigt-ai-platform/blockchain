package net.bigtangle.server.service;

/**
 * Service class responsible for managing caching of blockchain data.
 * Provides caching functionality for:
 * - Blocks and their serialized data
 * - Transaction rewards and evaluations
 * - Account balances and UTXOs
 * - MCMC (Markov Chain Monte Carlo) data
 * 
 * Uses Spring's caching annotations to manage cache operations:
 * - @Cacheable: Retrieves data from cache if available, otherwise executes method and caches result
 * - @CachePut: Updates cache with method return value
 * - @CacheEvict: Removes entries from cache, either individually or all entries
 * 
 * Cache eviction strategies:
 * - Individual cache entries can be evicted based on keys
 * - Entire caches can be cleared when needed
 * - Cache keys are carefully designed to ensure proper cache isolation
 * 
 * Important considerations:
 * - Cache consistency is maintained through careful eviction strategies
 * - Cache keys are designed to prevent collisions
 * - Cache operations are thread-safe
 */
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.Coin;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.UTXO;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.UTXOProviderException;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

@Service
public class CacheBlockService {
	private static final Logger logger = LoggerFactory.getLogger(CacheBlockService.class);
	@Autowired
	protected ObjectMapper jsonmapper;
	/**
	 * Retrieves a block's serialized data from cache if available, otherwise loads from database.
	 * 
	 * @param blockhash The SHA-256 hash of the block to retrieve
	 * @param store The block store interface implementation
	 * @return Serialized block data as byte array
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@Cacheable(value = "blocksCache", key = "#blockhash")
	public byte[] getBlock(Sha256Hash blockhash, BlockStoreInterface store) throws BlockStoreException {
		// logger.debug("read from database and no cache for: " + blockhash);
		byte[] block = store.getByte(blockhash);
//		 if(block==null ) {
//				logger.debug("block==null  " + blockhash);
//		 }
		return block;
	}

	/**
	 * Stores a block's serialized data in the cache.
	 * 
	 * @param block The block to cache
	 * @param store The block store interface implementation
	 * @return Serialized block data as byte array
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@CachePut(value = "blocksCache", key = "#block.hash")
	public byte[] cachePutBlock(final Block block, BlockStoreInterface store) throws BlockStoreException {
		// logger.debug("CachePut " + block.toString());
		return block.unsafeBitcoinSerialize();
	}

	/**
	 * Removes a block from the cache.
	 * 
	 * @param block The block to remove from cache
	 * @param store The block store interface implementation
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@CacheEvict(value = "blocksCache", key = "#block.hash")
	public void evictBlock(final Block block, BlockStoreInterface store) throws BlockStoreException {
		logger.debug("evictBlock {}", block.toString());
	}

	/**
	 * Retrieves the maximum confirmed reward from cache or database.
	 * 
	 * @param store The block store interface implementation
	 * @return TXReward object containing reward information
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	public TXReward getMaxConfirmedReward(BlockStoreInterface store) throws BlockStoreException {

		try {
			return new TXReward().parse(getMaxConfirmedRewardByte(store));
		} catch (IOException | BlockStoreException e) {
			throw new BlockStoreException(e);
		}

	}

	/**
	 * Retrieves the serialized maximum confirmed reward data from cache or database.
	 * 
	 * @param store The block store interface implementation
	 * @return Serialized reward data as byte array
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@Cacheable(value = "reward", key = "#store.getParams.getId")
	public byte[] getMaxConfirmedRewardByte(BlockStoreInterface store) throws BlockStoreException {
		// store.getParams().getId()
		TXReward reward = store.getMaxConfirmedReward();
		if (reward == null) {
			throw new BlockStoreException("MaxConfirmedReward is null");
		}
		return reward.toByteArray();
	}

	/**
	 * Clears all entries from the reward cache.
	 */
	@CacheEvict(value = "reward", allEntries = true)
	public synchronized void evictMaxConfirmedReward() {
	}

	/**
	 * Retrieves an account's balance from cache or database.
	 * 
	 * @param address The account address to retrieve balance for
	 * @param store The block store interface implementation
	 * @return List of Coin objects representing the account balance
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@Cacheable(value = "accountBalance", key = "#address")
	public List<Coin> getAccountBalance(String address, BlockStoreInterface store) throws BlockStoreException {
		logger.debug("getAccountBalance from database and no cache for: " + address);
		store.calculateAccount(address, null);
		List<Coin> accountBalance = store.getAccountBalance(address, null);
		if (accountBalance == null)
			return new ArrayList<Coin>();
		return accountBalance;

	}

	/**
	 * Removes an account's balance from the cache.
	 * 
	 * @param address The account address to evict from cache
	 * @param store The block store interface implementation
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@CacheEvict(value = "accountBalance", key = "#address")
	public void evictAccountBalance(String address, BlockStoreInterface store) throws BlockStoreException {
		// logger.debug("evictAccountBalance {}", address);
	}

	/**
	 * Retrieves open transaction outputs for an address from cache or database.
	 * 
	 * @param address The address to retrieve outputs for
	 * @param store The block store interface implementation
	 * @return List of serialized UTXO data as byte arrays
	 * @throws UTXOProviderException If there is an error retrieving UTXOs
	 * @throws JsonProcessingException If there is an error serializing UTXOs
	 */
	@Cacheable(value = "outputs", key = "#address")
	public List<byte[]> getOpenTransactionOutputs(String address, BlockStoreInterface store)
			throws UTXOProviderException, JsonProcessingException {

		List<UTXO> utxos = store.getOpenTransactionOutputs(address);
		List<byte[]> re = new ArrayList<>();
		for (UTXO u : utxos) {
			re.add( jsonmapper .writeValueAsBytes(u));
		}
		// logger.debug("getOpenTransactionOutputs from database and no cache for: " +
		// address + " size " + re.size());
		return re;
	}

	/**
	 * Removes an address's transaction outputs from the cache.
	 * 
	 * @param address The address to evict outputs for
	 * @param store The block store interface implementation
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@CacheEvict(value = "outputs", key = "#address")
	public void evictOutputs(String address, BlockStoreInterface store) throws BlockStoreException {
		// logger.debug("evictAccountBalance {}", address);
	}

	/**
	 * Clears all entries from the blocks cache.
	 * 
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@CacheEvict(value = "blocksCache", allEntries = true)
	public void evictBlock() throws BlockStoreException {
		logger.debug("evictBlock");
	}

	/**
	 * Clears all entries from the account balance cache.
	 * 
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@CacheEvict(value = "accountBalance", allEntries = true)
	public void evictAccountBalance() throws BlockStoreException {
		logger.debug("evictAccountBalance");
	}

	/**
	 * Clears all entries from the transaction outputs cache.
	 * 
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@CacheEvict(value = "outputs", allEntries = true)
	public void evictOutputs() throws BlockStoreException {
		logger.debug("evictOutputs");
	}

 
	/**
	 * Retrieves block evaluation data from cache or database.
	 * 
	 * @param blockhash The hash of the block to retrieve evaluation data for
	 * @param store The block store interface implementation
	 * @return Serialized block evaluation data as byte array
	 * @throws BlockStoreException If there is an error accessing the block store
	 * @throws JsonProcessingException If there is an error serializing the data
	 */
	@Cacheable(value = "BlockEvaluation", key = "#blockhash")
	public byte[] getBlockEvaluation(Sha256Hash blockhash, BlockStoreInterface store)
			throws BlockStoreException, JsonProcessingException {
		BlockEvaluation value = store.getBlockEvaluationsByhashs(blockhash);

		return jsonmapper.writeValueAsBytes(value);
	}

	/**
	 * Removes a block's evaluation data from the cache.
	 * 
	 * @param blockhash The hash of the block to evict evaluation data for
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@CacheEvict(value = "BlockEvaluation", key = "#blockhash")
	public void evictBlockEvaluation(Sha256Hash blockhash) throws BlockStoreException {

	}

	/**
	 * Clears all entries from the block evaluation cache.
	 */
	@CacheEvict(value = "BlockEvaluation", allEntries = true)
	public synchronized void evictBlockEvaluation() {
	}

	/**
	 * Retrieves block MCMC data from cache or database.
	 *
	 * @param blockhash The hash of the block to retrieve MCMC data for
	 * @param store The block store interface implementation
	 * @return Serialized MCMC data as byte array
	 * @throws BlockStoreException If there is an error accessing the block store
	 * @throws JsonProcessingException If there is an error serializing the data
	 */
	@Cacheable(value = "BlockMCMC", key = "#blockhash")
	public byte[] getBlockMCMC(Sha256Hash blockhash, BlockStoreInterface store)
			throws BlockStoreException, JsonProcessingException {
		BlockMCMC mcmc = store.getMCMC(blockhash);
		if (mcmc == null) {
			// Return default MCMC if not found (block hasn't been rated yet)
			mcmc = BlockMCMC.defaultBlockMCMC(blockhash);
		}
		return jsonmapper.writeValueAsBytes(mcmc);
	}

	/**
	 * Retrieves block MCMC data as a deserialized BlockMCMC object from cache or database.
	 * Avoids JSON round-trip overhead compared to getBlockMCMC().
	 * Uses a separate cache name to avoid ClassCastException with the byte[] cache.
	 */
	@Cacheable(value = "BlockMCMCObject", key = "#blockhash")
	public BlockMCMC getBlockMCMCAsObject(Sha256Hash blockhash, BlockStoreInterface store)
			throws BlockStoreException {
		BlockMCMC mcmc = store.getMCMC(blockhash);
		if (mcmc == null) {
			mcmc = BlockMCMC.defaultBlockMCMC(blockhash);
		}
		return mcmc;
	}

	/**
	 * Clears all entries from the MCMC cache.
	 */
	@CacheEvict(value = "BlockMCMC", allEntries = true)
	public synchronized void evictBlockMCMC() {
	}

	/**
	 * Evicts a single BlockMCMC entry from the cache.
	 */
	@CacheEvict(value = "BlockMCMC", key = "#blockhash")
	public void evictBlockMCMC(Sha256Hash blockhash) {
	}

	@CacheEvict(value = "BlockMCMCObject", key = "#blockhash")
	public void evictBlockMCMCObject(Sha256Hash blockhash) {
	}

	@CacheEvict(value = "BlockMCMCObject", allEntries = true)
	public synchronized void evictBlockMCMCObject() {
	}

	/**
	 * Evicts a batch of specific block hashes from both MCMC caches.
	 * More efficient than full eviction when only a subset of blocks changed.
	 */
	public void evictBlockMCMCBatch(Set<Sha256Hash> hashes) {
		for (Sha256Hash hash : hashes) {
			evictBlockMCMC(hash);
			evictBlockMCMCObject(hash);
		}
	}

	/**
	 * Retrieves a specific transaction output from cache or database.
	 * 
	 * @param utxo The UTXO to retrieve
	 * @param store The block store interface implementation
	 * @return Serialized UTXO data as byte array
	 * @throws BlockStoreException If there is an error accessing the block store
	 * @throws JsonProcessingException If there is an error serializing the UTXO
	 */
	@Cacheable(value = "utxos", key = "#utxo.hashCode")
	public byte[] getTransactionOutput(UTXO utxo, BlockStoreInterface store)
			throws BlockStoreException, JsonProcessingException {
		UTXO u = store.getTransactionOutput(utxo.getBlockHash(), utxo.getTxHash(), utxo.getIndex());
		return u == null ? null :  jsonmapper .writeValueAsBytes(u);
	}

	/**
	 * Removes a specific transaction output from the cache.
	 * 
	 * @param utxo The UTXO to evict from cache
	 * @param store The block store interface implementation
	 * @throws BlockStoreException If there is an error accessing the block store
	 */
	@CacheEvict(value = "utxos", key = "#utxo.hashCode")
	public void evictTransactionOutput(UTXO utxo, BlockStoreInterface store) throws BlockStoreException {

	}

	@Cacheable(value = "approverHashes", key = "#blockhash")
	public List<Sha256Hash> getApproverBlockHashes(Sha256Hash blockhash, BlockStoreInterface store)
			throws BlockStoreException {
		return store.getApproverBlockHashes(blockhash);
	}

	@CacheEvict(value = "approverHashes", allEntries = true)
	public void evictApproverHashes() {
	}

}
