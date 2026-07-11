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
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.BlockMCMC;
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
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Token;
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
import net.bigtangle.server.data.BatchBlock;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.data.DepthAndWeight;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.Orderresult;
import net.bigtangle.server.data.Rating;
import net.bigtangle.server.data.TipsQueue;

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

	/**
	 * Gets a {@link net.bigtangle.core.UTXO} with the given hash and index, or null
	 * if none is found
	 */
	UTXO getTransactionOutput(Sha256Hash blockHash, Sha256Hash txHash, long index) throws BlockStoreException;

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

	BlockEvaluation getTransactionOutputSpender(Sha256Hash blockHash, Sha256Hash txHash, long index)
			throws BlockStoreException;

	PriorityQueue<BlockWrap> getSolidBlocksInIntervalDescending(long cutoffHeight, long maxHeight)
			throws BlockStoreException;

	HashSet<BlockEvaluation> getBlocksToUnconfirm() throws BlockStoreException;

	TreeSet<BlockWrap> getBlocksToConfirm(long cutoffHeight, long maxHeight) throws BlockStoreException;

	BlockMCMC getMCMC(Sha256Hash hash) throws BlockStoreException;

	List<BlockMCMC> getMCMCDepth(long number) throws BlockStoreException;

	void updateBlockEvaluationWeightAndDepth(List<DepthAndWeight> depthAndWeight) throws BlockStoreException;

	void updateBlockEvaluationRating(List<Rating> ratings) throws BlockStoreException;

	void updateBlockEvaluationConfirmed(Sha256Hash blockhash, boolean confirmed) throws BlockStoreException;

	void updateBlockEvaluationMilestone(Sha256Hash blockhash, long milestone) throws BlockStoreException;

	void updateBlockEvaluationSolid(Sha256Hash blockhash, long solid) throws BlockStoreException;

	void resetMilestoneSolid(long milestone) throws BlockStoreException;

	void deleteMCMC(long chainlenght) throws BlockStoreException;

	/* TXOs */
	void updateTransactionOutputSpent(Sha256Hash prevBlockHash, Sha256Hash prevTxHash, long index, boolean b,
			Sha256Hash spenderBlock) throws BlockStoreException;

	void updateTransactionOutputConfirmed(Sha256Hash blockHash, Sha256Hash txHash, long index, boolean b)
			throws BlockStoreException;

	void updateAllTransactionOutputsConfirmed(Sha256Hash blockHash, boolean b) throws BlockStoreException;

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

	long getRewardDifficulty(Sha256Hash hash) throws BlockStoreException;

	void insertReward(Sha256Hash hash, Sha256Hash prevBlockHash, long difficulty, long chainLength)
			throws BlockStoreException;

	void updateRewardConfirmed(Sha256Hash hash, boolean b) throws BlockStoreException;

	void updateRewardSpent(Sha256Hash hash, boolean b, Sha256Hash spenderHash) throws BlockStoreException;

	Sha256Hash getRewardSpender(Sha256Hash hash) throws BlockStoreException;

	Sha256Hash getRewardPrevBlockHash(Sha256Hash hash) throws BlockStoreException;

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

	void insertTipsQueue(TipsQueue tipsQueue) throws BlockStoreException;

	void deleteTipsQueue(Sha256Hash hash) throws BlockStoreException;

	TipsQueue getTipsQueue() throws BlockStoreException;

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

	List<Sha256Hash> blocksNotMilestoneFromHeigth(long heigth) throws BlockStoreException;

	TXReward getRewardConfirmedAtHeight(long chainlength) throws BlockStoreException;

	List<Sha256Hash> getBlocksInMilestoneInterval(long minMilestone, long maxMilestone) throws BlockStoreException;

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

	Orderresult getMaxMilestoneOrderresult() throws BlockStoreException;

	Orderresult getMaxConfirmedOrderresult() throws BlockStoreException;

	List<Orderresult> getLowerConfirmedOrderresult(long milestone) throws BlockStoreException;

	void updateContractresultMilestone(Sha256Hash blockhash, long milestone) throws BlockStoreException;

	void updateOrderresultMilestone(Sha256Hash blockhash, long milestone) throws BlockStoreException;

	Contractresult getMaxMilestoneContractresult(String contracttokenid) throws BlockStoreException;

	Contractresult getMaxConfirmedContractresult(String contracttokenid) throws BlockStoreException;

	List<Contractresult> getLowerConfirmedContractresult(String contracttokenid, long milestone)
			throws BlockStoreException;

	List<ContractEventRecord> getContractEventRecordOpen(String tokenid) throws BlockStoreException;

	SpentBlockData getTransactionSpentBlock(Sha256Hash blockHash, Sha256Hash hash, long index)
			throws BlockStoreException;

	void saveAnchor(AnchorRecord anchor) throws BlockStoreException;

	AnchorRecord getAnchorByChainIdAndHeight(String chainId, long l1Height) throws BlockStoreException;

	List<AnchorRecord> getAnchorsByChainId(String chainId, long sinceHeight, int limit) throws BlockStoreException;

	AnchorRecord getLatestAnchorByChainId(String chainId) throws BlockStoreException;

	AnchorRecord getAnchorByBlockHash(Sha256Hash blockHash) throws BlockStoreException;

	void updateAnchorConfirmed(String chainId, long l1Height, boolean confirmed) throws BlockStoreException;

}
