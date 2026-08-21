/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.store;

import java.math.BigInteger;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

import jakarta.annotation.Nullable;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventCancel;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.OrderCancel;
import net.bigtangle.core.OrderExecutionResult;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.OutputsMulti;
import net.bigtangle.core.PayMultiSign;
import net.bigtangle.core.PayMultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SpentBlockData;
import net.bigtangle.core.AttestationData;
import net.bigtangle.core.StakeRecord;


import net.bigtangle.core.TXReward;
import net.bigtangle.core.Token;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UserData;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.UTXOProviderException;
import net.bigtangle.ordermatch.AVGMatchResult;
import net.bigtangle.ordermatch.MatchLastdayResult;
import net.bigtangle.ordermatch.MatchResult;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.data.VaultRecord;
import net.bigtangle.server.data.BatchBlock;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.Orderresult;

/**
 * <p>
 * An implementor of FullBlockStore saves Block and persistence objects to some
 * storage mechanism.
 * </p>
 *
 * An implementor of BlockStore saves StoredBlock objects to database. Different
 * implementations store them in different ways.
 * <p>
 * </p>
 */
public interface BlockStoreInterface {

	/**
	 * Saves the given block header+extra data. The key isn't specified explicitly
	 * as it can be calculated from the StoredBlock directly. Can throw if there is
	 * a problem with the underlying storage layer such as running out of disk
	 * space.
	 */
	void put(Block block) throws BlockStoreException;

	/**
	 * Returns the Block given a hash. The returned values block.getHash() method
	 * will be equal to the parameter. If no such block is found, returns null.
	 */
	Block get(Sha256Hash hash) throws BlockStoreException;

	byte[] getByte(Sha256Hash hash) throws BlockStoreException;

	/** Closes the store. */
	void close() throws BlockStoreException;

	/** Sets batch-path durability. No-op for stores that don't support it. */
	default void setBatchDurability(boolean asyncCommit) throws BlockStoreException {
	}

	/**
	 * Get the {@link net.bigtangle.params.NetworkParameters} of this store.
	 * 
	 * @return The network params.
	 */
	NetworkParameters getParams();

	boolean existBlock(Sha256Hash hash) throws BlockStoreException;

	List<UTXO> getOpenTransactionOutputs(String address) throws UTXOProviderException;

	List<UTXO> getOpenTransactionOutputs(List<Address> addresses) throws UTXOProviderException;

	List<UTXO> getOpenAllOutputs(String tokenid) throws UTXOProviderException;

	boolean getOutputConfirmation(Sha256Hash blockHash, Sha256Hash hash, long index) throws BlockStoreException;

	/** True when the given block is marked confirmed (blocks.confirmed). */
	boolean isBlockConfirmed(Sha256Hash blockHash) throws BlockStoreException;

	long countSpentOutputs(Sha256Hash txHash) throws BlockStoreException;

	/**
	 * Gets a {@link net.bigtangle.core.UTXO} with the given hash and index, or null
	 * if none is found
	 */
	UTXO getTransactionOutput(Sha256Hash blockHash, Sha256Hash txHash, long index) throws BlockStoreException;

	Map<Long, UTXO> getTransactionOutputs(Sha256Hash blockHash, Sha256Hash hash, Collection<Long> indices) throws BlockStoreException;

	/**
	 * Fetches the {@link net.bigtangle.core.UTXO} rows for many outpoints in a
	 * single batched query. Outpoints with no row (output never created, or
	 * created by a block not yet saved) are absent from the returned map.
	 */
	Map<TransactionOutPoint, UTXO> getTransactionOutputs(Collection<TransactionOutPoint> outpoints) throws BlockStoreException;

	/**
	 * Adds a {@link net.bigtangle.core.UTXO} to the list of unspent
	 * TransactionOutputs
	 */

	void addUnspentTransactionOutput(List<UTXO> utxos) throws BlockStoreException;

	/**
	 * <p>
	 * Begins/Commits/Aborts a database transaction.
	 * </p>
	 * <p>
	 * If abortDatabaseBatchWrite() is called by the same thread that called
	 * beginDatabaseBatchWrite(), any data writes between this call and
	 * abortDatabaseBatchWrite() made by the same thread should be discarded.
	 * </p>
	 *
	 * <p>
	 * Furthermore, any data written after a call to beginDatabaseBatchWrite()
	 * should not be readable by any other threads until commitDatabaseBatchWrite()
	 * has been called by this thread. Multiple calls to beginDatabaseBatchWrite()
	 * in any given thread should be ignored and treated as one call.
	 * </p>
	 */
	void beginDatabaseBatchWrite() throws BlockStoreException;

	void commitDatabaseBatchWrite() throws BlockStoreException;

	void abortDatabaseBatchWrite() throws BlockStoreException;

	void defaultDatabaseBatchWrite() throws BlockStoreException;

	void resetStore() throws BlockStoreException;

	void deleteStore() throws BlockStoreException;

	void create() throws BlockStoreException;

	/* Blocks */
	List<BlockWrap> getNotInvalidApproverBlocks(Sha256Hash hash) throws BlockStoreException;

	List<Sha256Hash> getApproverBlockHashes(Sha256Hash hash) throws BlockStoreException;

	BlockWrap getBlockWrap(Sha256Hash hash) throws BlockStoreException;

	List<BlockWrap> getBlockWraps(java.util.Collection<Sha256Hash> hashes) throws BlockStoreException;

	BlockEvaluation getTransactionOutputSpender(Sha256Hash blockHash, Sha256Hash txHash, long index)
			throws BlockStoreException;

	PriorityQueue<BlockWrap> getSolidBlocksInIntervalDescending(long cutoffHeight, long maxHeight)
			throws BlockStoreException;

	/** Lightweight topology query — returns only hash+prev hashes+height, no block bytes. */
	PriorityQueue<BlockWrap> getSolidBlockTopologyInInterval(long cutoffHeight, long maxHeight)
			throws BlockStoreException;

	HashSet<BlockEvaluation> getBlocksToUnconfirm() throws BlockStoreException;

	TreeSet<BlockWrap> getBlocksToConfirm(long cutoffHeight, long maxHeight) throws BlockStoreException;

	void updateBlockEvaluationConfirmed(Sha256Hash blockhash, boolean confirmed) throws BlockStoreException;
	void updateBlockEvaluationConfirmedBatch(List<Sha256Hash> blockHashes, List<Long> chainlengths)
			throws BlockStoreException;

	void updateBlockEvaluationChainlength(Sha256Hash blockhash, long chainlength) throws BlockStoreException;

	void updateBlockEvaluationSolid(Sha256Hash blockhash, long solid) throws BlockStoreException;

	void resetChainlengthSolid(long chainlength) throws BlockStoreException;

	/* TXOs */
	void updateTransactionOutputSpent(Sha256Hash prevBlockHash, Sha256Hash prevTxHash, long index, boolean b,
			@Nullable Sha256Hash spenderBlockHash) throws BlockStoreException;
	void updateTransactionOutputSpentBatch(List<Sha256Hash> prevBlockHashes, List<Sha256Hash> prevTxHashes,
			List<Long> indexes, Sha256Hash spenderBlockHash) throws BlockStoreException;
	void updateTransactionOutputSpentBatch(List<Sha256Hash> prevBlockHashes, List<Sha256Hash> prevTxHashes,
			List<Long> indexes, List<Sha256Hash> spenderBlockHashes) throws BlockStoreException;
	Map<TransactionOutPoint, OutputSpentStatus> getOutputSpentStatus(Collection<TransactionOutPoint> outpoints)
			throws BlockStoreException;
	void updateTransactionOutputConfirmed(Sha256Hash blockHash, Sha256Hash txHash, long index, boolean b)
			throws BlockStoreException;

	void updateAllTransactionOutputsConfirmed(Sha256Hash blockHash, boolean b) throws BlockStoreException;
	void updateAllTransactionOutputsConfirmedBatch(List<Sha256Hash> blockHashes, boolean b) throws BlockStoreException;

	void updateTransactionOutputSpendPending(List<UTXO> utxos) throws BlockStoreException;

	OrderRecord getOrder(Sha256Hash blockHash, Sha256Hash issuingMatcherBlockHash) throws BlockStoreException;

	void insertOrder(Collection<OrderRecord> records) throws BlockStoreException;

	void insertCancelOrder(OrderCancel orderCancel) throws BlockStoreException;

	void updateOrderCancelSpent(Set<Sha256Hash> cancels, Sha256Hash blockhash, Boolean spent)
			throws BlockStoreException;

	void updateOrderConfirmed(Collection<OrderRecord> orderRecords, boolean confirm) throws BlockStoreException;

	void updateOrderSpent(Collection<OrderRecord> orderRecords) throws BlockStoreException;

	HashMap<Sha256Hash, OrderRecord> getOrderMatchingIssuedOrders(Sha256Hash issuingMatcherBlockHash)
			throws BlockStoreException;

	void updateOrderBlockhash(Sha256Hash orderhansh, Sha256Hash collectinghash, boolean confirm, boolean spent,
			Sha256Hash spentBlock) throws BlockStoreException;
	void updateOrderPrevhash( Sha256Hash collectinghash, boolean confirm, boolean spent,
			Sha256Hash spentBlock) throws BlockStoreException;
	
	void prunedHistoryUTXO(Long maxRewardblock) throws BlockStoreException;

	void prunedPriceTicker(Long timeInSeconds) throws BlockStoreException;

	void prunedClosedOrders(Long timeInSeconds) throws BlockStoreException;

	void prunedBlocks(Long heigth, Long chain) throws BlockStoreException;

	TXReward getMaxConfirmedReward() throws BlockStoreException;

	List<TXReward> getAllConfirmedReward() throws BlockStoreException;

	boolean getRewardConfirmed(Sha256Hash hash) throws BlockStoreException;

	boolean getRewardSpent(Sha256Hash hash) throws BlockStoreException;

	long getRewardChainLength(Sha256Hash hash) throws BlockStoreException;

	void insertReward(Sha256Hash hash, Sha256Hash prevBlockHash, long chainLength)
			throws BlockStoreException;

	void updateRewardConfirmed(Sha256Hash hash, boolean b) throws BlockStoreException;

	void updateRewardSpent(Sha256Hash hash, boolean b, Sha256Hash spenderHash) throws BlockStoreException;

	Sha256Hash getRewardSpender(Sha256Hash hash) throws BlockStoreException;

	/* Transaction status tracking */
	void upsertTransactionStatus(net.bigtangle.server.data.TransactionStatusRecord record) throws BlockStoreException;

	void upsertTransactionStatuses(List<net.bigtangle.server.data.TransactionStatusRecord> records)
			throws BlockStoreException;

	net.bigtangle.server.data.TransactionStatusRecord getTransactionStatus(Sha256Hash txhash)
			throws BlockStoreException;

	List<net.bigtangle.server.data.TransactionStatusRecord> getTransactionStatusesByStatus(
			net.bigtangle.server.data.TransactionStatus status) throws BlockStoreException;

	List<net.bigtangle.server.data.TransactionStatusRecord> getTransactionStatusesByAddress(String address)
			throws BlockStoreException;

	Sha256Hash getRewardPrevBlockHash(Sha256Hash hash) throws BlockStoreException;

	/**
	 * Reward-chain children of a beacon: every confirmed/unconfirmed beacon whose
	 * reward parent ({@code txreward.prevblockhash}) equals {@code hash}. The
	 * LMD-GHOST fork choice for the REWARD CHAIN must walk these links — the DAG
	 * {@code prevblockhash} of a beacon points at its trunk/branch DAG tips, not
	 * its reward ancestor.
	 */
	List<Sha256Hash> getRewardChainChildren(Sha256Hash hash) throws BlockStoreException;

	/* Token TXOs */
	void insertToken(Sha256Hash blockhash, Token tokens) throws BlockStoreException;

	Token getTokenByBlockHash(Sha256Hash blockhash) throws BlockStoreException;

	List<Token> getTokenID(String tokenid) throws BlockStoreException;

	Sha256Hash getTokenPrevblockhash(Sha256Hash blockhash) throws BlockStoreException;

	SpentBlockData getTokenSpent(Sha256Hash blockhash) throws BlockStoreException;

	boolean getTokenAnyConfirmed(String tokenid, long tokenindex) throws BlockStoreException;

	BlockWrap getTokenIssuingConfirmedBlock(String tokenid, long tokenindex) throws BlockStoreException;

	BlockWrap getDomainIssuingConfirmedBlock(String domainName, String domainPred, long index)
			throws BlockStoreException;

	void updateTokenSpent(Sha256Hash blockhash, boolean b, Sha256Hash spenderBlockHash) throws BlockStoreException;

	void updateTokenConfirmed(Sha256Hash blockhash, boolean confirmed) throws BlockStoreException;

	List<OrderRecord> getAllOpenOrdersSorted(List<String> addresses, String tokenid) throws BlockStoreException;

	List<UTXO> getAllAvailableUTXOsSorted() throws BlockStoreException;

	List<Token> getTokensList(Set<String> tokenids) throws BlockStoreException;

	List<Token> getTokenTypeList(int type) throws BlockStoreException;

	List<Token> getTokensList(String name) throws BlockStoreException;

	/**
	 * Search confirmed tokens by token name OR token id (case-insensitive
	 * substring), capped at 500 results.
	 */
	List<Token> getTokensByNameOrId(String keyword) throws BlockStoreException;

	Map<String, BigInteger> getTokenAmountMap() throws BlockStoreException;

	List<BlockEvaluationDisplay> getSearchBlockEvaluations(List<String> address, String lastestAmount, long height,
			long maxblocks) throws BlockStoreException;

	List<Block> findRetryBlocks(long minheight) throws BlockStoreException;

	List<BlockEvaluationDisplay> getSearchBlockEvaluationsByhashs(List<String> blockhashs) throws BlockStoreException;

	BlockEvaluation getBlockEvaluationsByhashs(Sha256Hash blockhashs) throws BlockStoreException;

	List<byte[]> blocksFromChainLength(long start, long end) throws BlockStoreException;

	List<byte[]> blocksFromNonChainHeigth(long heigth) throws BlockStoreException;

	void updateMultiSignBlockBitcoinSerialize(String tokenid, long tokenindex, byte[] bytes) throws BlockStoreException;

	List<MultiSignAddress> getMultiSignAddressListByTokenidAndBlockHashHex(String tokenid, Sha256Hash prevblockhash)
			throws BlockStoreException;

	void insertMultiSignAddress(MultiSignAddress multiSignAddress) throws BlockStoreException;

	List<MultiSign> getMultiSignListByTokenid(String tokenid, int tokenindex, Set<String> addresses, boolean isSign)
			throws BlockStoreException;

	List<OutputsMulti> queryOutputsMultiByHashAndIndex(byte[] hash, long index) throws BlockStoreException;

	List<MultiSign> getMultiSignListByAddress(String address) throws BlockStoreException;

	List<MultiSign> getMultiSignListByTokenidAndAddress(String tokenid, String address) throws BlockStoreException;

	int getCountMultiSignAlready(String tokenid, long tokenindex, String address) throws BlockStoreException;

	int countMultiSign(String tokenid, long tokenindex, int sign) throws BlockStoreException;

	void saveMultiSign(MultiSign multiSign) throws BlockStoreException;

	void updateMultiSign(String tokenid, long tokenindex, String address, byte[] bytes, int sign)
			throws BlockStoreException;

	void deleteMultiSign(String tokenid) throws BlockStoreException;

	void insertOutputsMulti(OutputsMulti outputsMulti) throws BlockStoreException;

	UserData queryUserDataWithPubKeyAndDataclassname(String dataclassname, String pubKey) throws BlockStoreException;

	void insertUserData(UserData userData) throws BlockStoreException;

	void updateUserData(UserData userData) throws BlockStoreException;

	void insertPayPayMultiSign(PayMultiSign payMultiSign) throws BlockStoreException;

	void insertPayMultiSignAddress(PayMultiSignAddress payMultiSignAddress) throws BlockStoreException;

	void updatePayMultiSignAddressSign(String orderid, String pubKey, int sign, byte[] signInputData)
			throws BlockStoreException;

	PayMultiSign getPayMultiSignWithOrderid(String orderid) throws BlockStoreException;

	List<PayMultiSignAddress> getPayMultiSignAddressWithOrderid(String orderid) throws BlockStoreException;

	void updatePayMultiSignBlockhash(String orderid, byte[] blockhash) throws BlockStoreException;

	List<PayMultiSign> getPayMultiSignList(List<String> pubKeys) throws BlockStoreException;

	int getCountPayMultiSignAddressStatus(String orderid) throws BlockStoreException;

	UTXO getOutputsWithHexStr(byte[] hash, long outputindex) throws BlockStoreException;

	List<UserData> getUserDataListWithBlocktypePubKeyList(int blocktype, List<String> pubKeyList)
			throws BlockStoreException;

	byte[] getSettingValue(String name) throws BlockStoreException;

	Token getCalMaxTokenIndex(String tokenid) throws BlockStoreException;

	void insertBatchBlock(Block block) throws BlockStoreException;

	void deleteBatchBlock(Sha256Hash hash) throws BlockStoreException;

	List<BatchBlock> getBatchBlockList() throws BlockStoreException;

	List<UTXO> getOutputsHistory(String fromaddress, String toaddress, Long starttime, Long endtime)
			throws BlockStoreException;

	void insertSubtanglePermission(String pubkey, String userdatapubkey, String status) throws BlockStoreException;

	void deleteSubtanglePermission(String pubkey) throws BlockStoreException;

	void updateSubtanglePermission(String pubkey, String userdataPubkey, String status) throws BlockStoreException;

	List<Map<String, String>> getAllSubtanglePermissionList() throws BlockStoreException;

	List<Map<String, String>> getSubtanglePermissionListByPubkey(String pubkey) throws BlockStoreException;

	List<Map<String, String>> getSubtanglePermissionListByPubkeys(List<String> pubkeys) throws BlockStoreException;

	void insertMyserverblocks(Sha256Hash prevhash, Sha256Hash hash, Long inserttime) throws BlockStoreException;

	void deleteMyserverblocks(Sha256Hash prevhash) throws BlockStoreException;

	boolean existMyserverblocks(Sha256Hash prevhash) throws BlockStoreException;

	void insertMatchingEvent(List<MatchResult> matchresults) throws BlockStoreException;

	void deleteMatchingEvents(String hashString) throws BlockStoreException;

	List<MatchLastdayResult> getLastMatchingEvents(Set<String> tokenId, String basetoken) throws BlockStoreException;

	Token queryDomainnameToken(Sha256Hash domainNameBlockHash) throws BlockStoreException;

	Token getTokensByDomainname(String domainname) throws BlockStoreException;

	List<Sha256Hash> blocksNotChainlengthFromHeigth(long heigth) throws BlockStoreException;

	TXReward getRewardConfirmedAtHeight(long chainlength) throws BlockStoreException;

	List<Sha256Hash> getBlocksInChainlengthInterval(long minChainlength, long maxChainlength) throws BlockStoreException;

	List<Sha256Hash> getBlocksByPrevHash(Sha256Hash prev) throws BlockStoreException;

	List<OrderCancel> getOrderCancelByOrderBlockHash(HashSet<String> orderBlockHashs) throws BlockStoreException;

	boolean getTokennameAndDomain(String tokenname, String domainpre) throws BlockStoreException;

	List<MatchLastdayResult> getTimeBetweenMatchingEvents(String tokenids, String basetoken, Long startDate,
			Long endDate, int count) throws BlockStoreException;

	List<MatchLastdayResult> getTimeAVGBetweenMatchingEvents(String tokenids, String basetoken, Long startDate,
			Long endDate, int count) throws BlockStoreException;

	void insertAccessPermission(String pubKey, String accessToken) throws BlockStoreException;

	int getCountAccessPermissionByPubKey(String pubKey, String accessToken) throws BlockStoreException;

	void insertAccessGrant(String address) throws BlockStoreException;

	void deleteAccessGrant(String address) throws BlockStoreException;

	int getCountAccessGrantByAddress(String address) throws BlockStoreException;

	void updateDatabse() throws BlockStoreException, SQLException;

	void insertChainBlockQueue(ChainBlockQueue chainBlockQueue) throws BlockStoreException;

	List<ChainBlockQueue> selectChainblockqueue(boolean orphan, int limit) throws BlockStoreException;

	void deleteChainBlockQueue(List<ChainBlockQueue> chainBlockQueues) throws BlockStoreException;

	void deleteAllChainBlockQueue() throws BlockStoreException;

	LockObject selectLockobject(String lockobjectid) throws BlockStoreException;

	void deleteLockobject(String lockobjectid) throws BlockStoreException;

	void deleteAllLockobject() throws BlockStoreException;

	void insertLockobject(LockObject lockobject) throws BlockStoreException;

	void batchAddAvgPrice() throws Exception;

	void saveAvgPrice(AVGMatchResult matchResult) throws BlockStoreException;

	List<AVGMatchResult> queryTickerByTime(long starttime, long endtime) throws BlockStoreException;

	void insertContractEvent(Collection<ContractEventRecord> records) throws BlockStoreException;

	ContractEventRecord getContractEvent(Sha256Hash blockhash, Sha256Hash collectionhash) throws BlockStoreException;

	List<ContractEventRecord> getContractEvents(Sha256Hash blockhash) throws BlockStoreException;

	void updateContractEventSpent(Collection<ContractEventRecord> records) throws BlockStoreException;

	void updateContractEventBlockhash(Sha256Hash blockhash, Sha256Hash collectinghash, boolean confirm, boolean spent,
			Sha256Hash spentBlock) throws BlockStoreException;
	
	void updateContractEventPrevhash(Sha256Hash collectinghash, boolean confirm, boolean spent,
			Sha256Hash spentBlock) throws BlockStoreException;
	
	Map<Sha256Hash, ContractEventRecord> getContractEventPrev(String contractid, Sha256Hash prevHash)
			throws BlockStoreException;

	void insertContractResult(ContractExecutionResult record, Sha256Hash blockhash) throws BlockStoreException;

	/** Persists a serialized EVM transaction receipt keyed by its EVM block hash. */
	void insertEVMReceipt(Sha256Hash blockhash, String contracttokenid, byte[] receipt) throws BlockStoreException;

	/** Reads the serialized EVM receipt for the given EVM block hash, or null. */
	byte[] getEVMReceipt(Sha256Hash blockhash) throws BlockStoreException;

	/** Lists the serialized EVM receipts for the given contract token, newest first. */
	List<byte[]> getEVMReceiptsByToken(String contracttokenid) throws BlockStoreException;

	void updateContractResultSpent(Sha256Hash contractResult, Sha256Hash spentBlock, boolean spent)
			throws BlockStoreException;

	void updateContractResultConfirmed(Sha256Hash contract, boolean confirm) throws BlockStoreException;

	Contractresult getContractresult(Sha256Hash blockhash) throws BlockStoreException;

	List<Contractresult> getContractresultWithPrev(Sha256Hash prev) throws BlockStoreException;

	void updateOrderResultConfirmed(Sha256Hash contract, boolean confirm) throws BlockStoreException;

	Orderresult getOrderResult(Sha256Hash blockhash) throws BlockStoreException;

	List<Orderresult> getOrderresultWithPrev(Sha256Hash prevhash) throws BlockStoreException;

	void insertOrderResult(OrderExecutionResult record, Sha256Hash blockhash) throws BlockStoreException;

	void updateOrderResultSpent(Sha256Hash result, Sha256Hash spentBlock, boolean spent) throws BlockStoreException;

	List<Coin> getAccountBalance(String address, String tokenid) throws BlockStoreException;

	void calculateAccount(String address, String tokenid) throws BlockStoreException;

	void insertContractEventCancel(ContractEventCancel contractEventCancel) throws BlockStoreException;

	void updateContractEventCancelSpent(Set<Sha256Hash> cancels, Sha256Hash blockhash, Boolean spent)
			throws BlockStoreException;

	Orderresult getMaxRewardChainlengthOrderresult() throws BlockStoreException;

	Orderresult getMaxConfirmedOrderresult() throws BlockStoreException;

	List<Orderresult> getLowerConfirmedOrderresult(long chainlength) throws BlockStoreException;

	void updateContractresultChainlength(Sha256Hash blockhash, long chainlength) throws BlockStoreException;

	void updateOrderresultChainlength(Sha256Hash blockhash, long chainlength) throws BlockStoreException;

	Contractresult getMaxRewardChainlengthContractresult(String contracttokenid) throws BlockStoreException;

	Contractresult getMaxConfirmedContractresult(String contracttokenid) throws BlockStoreException;

	List<Contractresult> getLowerConfirmedContractresult(String contracttokenid, long chainlength)
			throws BlockStoreException;

	List<ContractEventRecord> getContractEventRecordOpen(String tokenid) throws BlockStoreException;

	SpentBlockData getTransactionSpentBlock(Sha256Hash blockHash, Sha256Hash hash, long index)
			throws BlockStoreException;

	void saveAnchor(AnchorRecord anchor) throws BlockStoreException;

	AnchorRecord getAnchorByChainIdAndHeight(String chainId, long l1Height) throws BlockStoreException;

	List<AnchorRecord> getAnchorsByChainId(String chainId, long sinceHeight, int limit) throws BlockStoreException;

	AnchorRecord getLatestAnchorByChainId(String chainId) throws BlockStoreException;

	AnchorRecord getAnchorByBlockHash(Sha256Hash blockHash) throws BlockStoreException;

	/** All anchor records, across every chain (used for peg-out retry scans). */
	List<AnchorRecord> getAllAnchors() throws BlockStoreException;

	void updateAnchorConfirmed(String chainId, long l1Height, boolean confirmed) throws BlockStoreException;

	void saveVaultUTXO(VaultRecord vault) throws BlockStoreException;

	List<VaultRecord> getVaultUTXOsByChainId(String chainId, boolean spent) throws BlockStoreException;

	void markVaultUTXOSpent(String chainId, Sha256Hash utxoBlockHash, long utxoIndex) throws BlockStoreException;

	void saveStakeDeposit(StakeRecord stake) throws BlockStoreException;

	StakeRecord getStakeDeposit(byte[] pubkey) throws BlockStoreException;

	/** Accumulates a top-up deposit onto an existing pubkey's record. */
	void updateStakeDepositAmount(byte[] pubkey, long newAmount, Sha256Hash blockHash, Sha256Hash txHash,
			long activatedEpoch) throws BlockStoreException;

	/** Looks up a deposit by the STAKE block hash that created it (reorg revert). */
	List<StakeRecord> getStakeDepositsByBlockHash(Sha256Hash blockHash) throws BlockStoreException;

	/** Looks up a deposit whose bonded output is (blockHash, txHash) (bond enforcement). */
	StakeRecord getStakeDepositByOutput(Sha256Hash blockHash, Sha256Hash txHash) throws BlockStoreException;

	List<StakeRecord> getActiveStakeDeposits() throws BlockStoreException;

	/**
	 * Active validators whose activation epoch is {@code <= currentEpoch} — the
	 * selectable/weighted set (activation delay: a deposit is only active once
	 * its future activation epoch has been reached on the chain).
	 */
	List<StakeRecord> getActiveStakeDeposits(long currentEpoch) throws BlockStoreException;

	List<StakeRecord> getAllStakeDeposits() throws BlockStoreException;

	void updateStakeActivation(byte[] pubkey, long epoch) throws BlockStoreException;

	void updateStakeSlashing(byte[] pubkey, long withdrawableEpoch) throws BlockStoreException;

	/** Marks a validator as voluntarily exiting (distinct from slashed). */
	void updateStakeExit(byte[] pubkey, long withdrawableEpoch) throws BlockStoreException;

	/** Clears the voluntary-exit flag (reorg revert). */
	void clearStakeExit(byte[] pubkey) throws BlockStoreException;

	void releaseStakeDeposit(byte[] pubkey) throws BlockStoreException;

	/** Removes the stake record entirely (withdrawal): the bonded output becomes spendable. */
	void deleteStakeDeposit(byte[] pubkey) throws BlockStoreException;

	/** Clears the slashed flag and withdrawable epoch (reorg revert of a SLASHING block). */
	void clearStakeSlashing(byte[] pubkey) throws BlockStoreException;

	/** Clears ONLY the withdrawable epoch, leaving slashed/exiting flags intact. */
	void clearStakeWithdrawable(byte[] pubkey) throws BlockStoreException;

	void saveAttestationVote(Sha256Hash blockHash, byte[] pubkey, long weight, long slot) throws BlockStoreException;

	/** Removes a validator's vote on {@code blockHash} when it retracts it (LMD: latest vote only). */
	void deleteAttestationVote(Sha256Hash blockHash, byte[] pubkey) throws BlockStoreException;

	/**
	 * A validator's latest attestation vote (one per validator after LMD
	 * retraction): the voted beacon, the weight at vote time, and the slot.
	 */
	class LatestVote {
		public final byte[] pubkey;
		public final Sha256Hash blockHash;
		public final long weight;
		public final long slot;

		public LatestVote(byte[] pubkey, Sha256Hash blockHash, long weight, long slot) {
			this.pubkey = pubkey;
			this.blockHash = blockHash;
			this.weight = weight;
			this.slot = slot;
		}
	}

	/** Returns every validator's latest stored vote (for LMD restore). */
	List<LatestVote> getLatestAttestationVotes() throws BlockStoreException;

	List<AttestationData> getAttestationsForSlot(long slot) throws BlockStoreException;

	Map<Sha256Hash, Long> getSummedAttestationVotes() throws BlockStoreException;

	void savePosState(String service, String key, byte[] value) throws BlockStoreException;

	byte[] getPosState(String service, String key) throws BlockStoreException;

	Map<String, byte[]> getPosStateByService(String service) throws BlockStoreException;

	/**
	 * Returns only the pos_state entries of {@code service} whose key starts with
	 * {@code keyPrefix}. Pushed into SQL so the caller never materializes the
	 * whole service map (bounds the per-slot proposer path to the attestations of
	 * that slot instead of every persisted attestation).
	 */
	Map<String, byte[]> getPosStateByServicePrefix(String service, String keyPrefix) throws BlockStoreException;

	/**
	 * Deletes every pos_state entry of {@code service} whose key is in
	 * {@code [fromKey, beforeKey)}. Used by the epoch-boundary prune to drop
	 * stale attestation rows in a single statement instead of per-key.
	 */
	void deletePosStateByServiceKeyRange(String service, String fromKey, String beforeKey)
			throws BlockStoreException;

	void deletePosState(String service, String key) throws BlockStoreException;

	/**
	 * The domain the store is provisioned for. A layer-minimal store only creates
	 * the tables of its own domain (plus the shared core domain), so cross-domain
	 * reads must be skipped — see {@link #hasOrderDomain()} and
	 * {@link #hasContractDomain()}.
	 */
	StoreDomain getStoreDomain();

	/** True when this store owns the order-matching tables. */
	default boolean hasOrderDomain() {
		return getStoreDomain() == StoreDomain.ORDER || getStoreDomain() == StoreDomain.ALL;
	}

	/** True when this store owns the contract/EVM tables. */
	default boolean hasContractDomain() {
		return getStoreDomain() == StoreDomain.CONTRACT || getStoreDomain() == StoreDomain.ALL;
	}

	/** Which domain's tables a store is provisioned with. */
	enum StoreDomain {
		/** Layer 0 / core chain tables only (blocks, UTXO, token, stake, ...). */
		CORE,
		/** Core + order-matching tables (l1-order). */
		ORDER,
		/** Core + contract/EVM tables (l1-contract). */
		CONTRACT,
		/** All domains (legacy full store). */
		ALL
	}

	/** Lean spent-status snapshot of an output, used by conflict resolution. */
	record OutputSpentStatus(boolean confirmed, Sha256Hash spenderBlockHash) {
	}

}
