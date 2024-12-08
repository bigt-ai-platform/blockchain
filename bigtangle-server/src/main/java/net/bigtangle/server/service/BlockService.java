package net.bigtangle.server.service;

import java.util.*;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Block.Type;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.NoBlockException;
import net.bigtangle.core.exception.ProtocolException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.core.exception.VerificationException.ConflictPossibleException;
import net.bigtangle.core.exception.VerificationException.ProofOfWorkException;
import net.bigtangle.core.exception.VerificationException.UnsolidException;
import net.bigtangle.core.response.AbstractResponse;
import net.bigtangle.core.response.GetBlockEvaluationsResponse;
import net.bigtangle.core.response.GetBlockListResponse;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.store.FullBlockStore;
import net.bigtangle.store.FullBlockStoreImpl;
import net.bigtangle.utils.DomainValidator;
import net.bigtangle.utils.Gzip;

/**
 * <p>
 * Provides services for blocks.
 * </p>
 */
@Service
public class BlockService {

	@Autowired
	protected StoreService storeService;

	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	FullBlockStoreImpl blockgraph;

	@Autowired
	protected ServerConfiguration serverConfiguration;

	@Autowired
	protected TipsService tipService;
	@Autowired
	protected CacheBlockService cacheBlockService;

	private static final Logger logger = LoggerFactory.getLogger(BlockService.class);

	public Block getBlock(Sha256Hash blockhash, FullBlockStore store) throws BlockStoreException {
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService);
		return serviceBase.getBlock(blockhash, store);
	}

	public BlockWrap getBlockWrap(Sha256Hash blockhash, FullBlockStore store)
			throws BlockStoreException {
		return new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService).getBlockWrap(blockhash, store);
	}

	public AbstractResponse searchBlock(Map<String, Object> request, FullBlockStore store) throws BlockStoreException {
		@SuppressWarnings("unchecked")
		List<String> address = (List<String>) request.get("address");
		String lastestAmount = request.get("lastestAmount") == null ? "0" : request.get("lastestAmount").toString();
		long height = request.get("height") == null ? 0L : Long.parseLong(request.get("height").toString());
		List<BlockEvaluationDisplay> evaluations = store.getSearchBlockEvaluations(address, lastestAmount, height,
				serverConfiguration.getMaxsearchblocks());
		return GetBlockEvaluationsResponse.create(evaluations);
	}

	public AbstractResponse searchBlockByBlockHashs(Map<String, Object> request, FullBlockStore store)
			throws BlockStoreException {
		@SuppressWarnings("unchecked")
		List<String> blockhashs = (List<String>) request.get("blockhashs");
		List<BlockEvaluationDisplay> evaluations = store.getSearchBlockEvaluationsByhashs(blockhashs);

		return GetBlockEvaluationsResponse.create(evaluations);
	}

	public void batchBlock(Block block, FullBlockStore store) throws BlockStoreException {

		store.insertBatchBlock(block);
	}

	public void insertMyserverblocks(Sha256Hash prevhash, Sha256Hash hash, Long inserttime, FullBlockStore store)
			throws BlockStoreException {

		store.insertMyserverblocks(prevhash, hash, inserttime);
	}

	public boolean existMyserverblocks(Sha256Hash prevhash, FullBlockStore store) throws BlockStoreException {

		return store.existMyserverblocks(prevhash);
	}

	public void deleteMyserverblocks(Sha256Hash prevhash, FullBlockStore store) throws BlockStoreException {

		store.deleteMyserverblocks(prevhash);
	}

	public GetBlockListResponse blocksFromChainLength(Long start, Long end, FullBlockStore store)
			throws BlockStoreException {

		return GetBlockListResponse.create(store.blocksFromChainLength(start, end));
	}

	public GetBlockListResponse blocksFromNonChainHeigth(long cutoffHeight, FullBlockStore store)
			throws BlockStoreException {

		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
		long my = getCurrentCutoffHeight(maxConfirmedReward, store);
		return GetBlockListResponse.create(store.blocksFromNonChainHeigth(Math.max(cutoffHeight, my)));
	}

	public Block getNewBlockPrototype(FullBlockStore store) throws BlockStoreException {
		Pair<BlockWrap, BlockWrap> tipsToApprove = getValidatedBlockPair(store);
		Block b = Block.createBlock(networkParameters, tipsToApprove.getLeft().getBlock(),
				tipsToApprove.getRight().getBlock());
		b.setMinerAddress(Address.fromBase58(networkParameters, serverConfiguration.getMineraddress()).getHash160());

		return b;
	}

	/*
	 * prefer tip from two different previous block. This is modified mcmc
	 */
	private Pair<BlockWrap, BlockWrap> getValidatedBlockPair(FullBlockStore store) throws BlockStoreException {
		Pair<BlockWrap, BlockWrap> candidate = tipService.getValidatedBlockPair(store);

		if (!candidate.getLeft().equals(candidate.getRight())) {
			return candidate;
		}
		for (int i = 0; i < 2; i++) {
			Pair<BlockWrap, BlockWrap> paar = tipService.getValidatedBlockPair(store);
			if (!paar.getLeft().getBlock().getHash().equals(paar.getRight().getBlock().getHash())) {
				return paar;
			}
		}
		return candidate;
	}

	public boolean getUTXOSpent(TransactionOutPoint txout, FullBlockStore store) throws BlockStoreException {
		return store.getTransactionOutput(txout.getBlockHash(), txout.getTxHash(), txout.getIndex()).isSpent();
	}

	public UTXO getUTXO(TransactionOutPoint out, FullBlockStore store) throws BlockStoreException {
		return store.getTransactionOutput(out.getBlockHash(), out.getTxHash(), out.getIndex());
	}

	/*
	 * Block byte[] bytes
	 */
	public Optional<Block> addConnectedFromKafka(byte[] key, byte[] bytes) {

		try {
            logger.debug("addConnectedFromKafka from sendkey:{}", Arrays.toString(key));
			return addConnected(Gzip.decompressOut(bytes), true);
		} catch (VerificationException e) {
			return Optional.empty();
		} catch (Exception e) {
            logger.debug("addConnectedFromKafka with sendkey:{}", Arrays.toString(key), e);
			return Optional.empty();
		}

	}

	/*
	 * Block byte[] bytes
	 */
	public Optional<Block> addConnected(byte[] bytes, boolean allowUnsolid)
			throws ProtocolException, BlockStoreException {
		if (bytes == null)
			return Optional.empty();
		Block makeBlock = networkParameters.getDefaultSerializer().makeBlock(bytes);
        logger.debug(" addConnected  Blockhash={} height ={} block: {}", makeBlock.getHashAsString(), makeBlock.getHeight(), makeBlock);
		return addConnectedBlock(makeBlock, allowUnsolid);
	}

	public Optional<Block> addConnectedBlock(Block block, boolean allowUnsolid) throws BlockStoreException {
		FullBlockStore store = storeService.getStore();
		try {
			if (!store.existBlock(block.getHash())) {
				try {
					if (block.getBlockType() == Type.BLOCKTYPE_REWARD) {
                        logger.debug(" connected received chain block  {}", block.getLastMiningRewardBlock());
					}
					blockgraph.add(block, allowUnsolid, store);
					// removeBlockPrototype(block,store);
					return Optional.of(block);
				} catch (ProofOfWorkException | UnsolidException e) {
					return Optional.empty();
				} catch (Exception e) {
                    logger.debug(" cannot add block: Blockhash={} height ={} block: {}", block.getHashAsString(), block.getHeight(), block, e);
					return Optional.empty();

				}
			}
		} finally {
			store.close();
		}

		return Optional.empty();
	}

	public void adjustHeightRequiredBlocks(Block block, FullBlockStore store)
			throws BlockStoreException, NoBlockException {
		block = adjustPrototype(block, store);
		long h = calcHeightRequiredBlocks(block, store);
		if (h > block.getHeight()) {
            logger.debug("adjustHeightRequiredBlocks{} to {}", block, h);
			block.setHeight(h);
		}
	}

	public Block adjustPrototype(Block block, FullBlockStore store) throws BlockStoreException {
		// two hours for just getBlockPrototype
		int delaySeconds = 7200;

		if (block.getTimeSeconds() < System.currentTimeMillis() / 1000 - delaySeconds) {
            logger.debug("adjustPrototype {}", block);
			Block newblock = getNewBlockPrototype(store);
			for (Transaction transaction : block.getTransactions()) {
				newblock.addTransaction(transaction);
			}
			return newblock;
		}
		return block;
	}

	public long calcHeightRequiredBlocks(Block block, FullBlockStore store) throws BlockStoreException {

		Set<Sha256Hash> allrequireds = new HashSet<>();
		List<Block> result = new ArrayList<>();
		allrequireds.add(block.getPrevBlockHash());
		allrequireds.add(block.getPrevBranchBlockHash());
		for (Sha256Hash pred : allrequireds)
			result.add(store.get(pred));

		long height = 0;
		for (Block b : result) {
			height = Math.max(height, b.getHeight());
		}
		return height + 1;
	}

	public long getCurrentMaxHeight(TXReward maxConfirmedReward, FullBlockStore store) throws BlockStoreException {
		// TXReward maxConfirmedReward = store.getMaxConfirmedReward();
		if (maxConfirmedReward == null)
			return NetworkParameters.FORWARD_BLOCK_HORIZON;
		return store.get(maxConfirmedReward.getBlockHash()).getHeight() + NetworkParameters.FORWARD_BLOCK_HORIZON;
	}

	public long getCurrentCutoffHeight(TXReward maxConfirmedReward, FullBlockStore store) throws BlockStoreException {
		// TXReward maxConfirmedReward = store.getMaxConfirmedReward();
		if (maxConfirmedReward == null)
			return 0;
		long chainlength = Math.max(0, maxConfirmedReward.getChainLength() - NetworkParameters.MILESTONE_CUTOFF);
		TXReward confirmedAtHeightReward = store.getRewardConfirmedAtHeight(chainlength);
		return store.get(confirmedAtHeightReward.getBlockHash()).getHeight();
	}

	public long getRewardCutoffHeight(Sha256Hash prevRewardHash, FullBlockStore store) throws BlockStoreException {

		Sha256Hash currPrevRewardHash = prevRewardHash;
		for (int i = 0; i < NetworkParameters.MILESTONE_CUTOFF; i++) {
			Block currRewardBlock;

			currRewardBlock = getBlock(currPrevRewardHash, store);
			RewardInfo currRewardInfo = new RewardInfo()
					.parseChecked(currRewardBlock.getTransactions().get(0).getData());
			if (currPrevRewardHash.equals(networkParameters.getGenesisBlock().getHash()))
				return 0;

			currPrevRewardHash = currRewardInfo.getPrevRewardHash();

		}
		return store.get(currPrevRewardHash).getHeight();
	}

	/*
	 * failed blocks without conflict for retry
	 */
	public AbstractResponse findRetryBlocks(Map<String, Object> request, FullBlockStore store)
			throws BlockStoreException {
		@SuppressWarnings("unchecked")
		List<String> address = (List<String>) request.get("address");
		String lastestAmount = request.get("lastestAmount") == null ? "0" : request.get("lastestAmount").toString();
		long height = request.get("height") == null ? 0L : Long.parseLong(request.get("height").toString());
		List<BlockEvaluationDisplay> evaluations = store.getSearchBlockEvaluations(address, lastestAmount, height,
				serverConfiguration.getMaxsearchblocks());
		return GetBlockEvaluationsResponse.create(evaluations);
	}

	public void checkBlockBeforeSave(Block block, FullBlockStore store) throws BlockStoreException {

		block.verifyHeader();
		if (!checkPossibleConflict(block, store))
			throw new ConflictPossibleException("Conflict Possible");
		checkDomainname(block);
	}

	public void checkDomainname(Block block) {
        if (Objects.requireNonNull(block.getBlockType()) == Type.BLOCKTYPE_TOKEN_CREATION) {
            TokenInfo currentToken = new TokenInfo().parseChecked(block.getTransactions().get(0).getData());
            if (TokenType.domainname.ordinal() == currentToken.getToken().getTokentype()) {
                if (!DomainValidator.getInstance().isValid(currentToken.getToken().getTokenname()))
                    throw new VerificationException("Domain name is not valid.");
            }
        }
	}

	/*
	 * Transactions in a block may has spent output, It is not final that the reject
	 * of the block Return false, if there is possible conflict
	 */
	public boolean checkPossibleConflict(Block block, FullBlockStore store) throws BlockStoreException {
		// All used transaction outputs
		final List<Transaction> transactions = block.getTransactions();
		for (final Transaction tx : transactions) {
			if (!tx.isCoinBase()) {
				for (int index = 0; index < tx.getInputs().size(); index++) {
					TransactionInput in = tx.getInputs().get(index);

					UTXO b = store.getTransactionOutput(in.getOutpoint().getBlockHash(), in.getOutpoint().getTxHash(),
							in.getOutpoint().getIndex());
					if (b != null && b.isConfirmed() && b.isSpent()) {
						// there is a confirmed output, conflict is very
						// possible
						return false;
					}
					if (b != null && !b.isConfirmed() && !checkSpendpending(b)) {
						// there is a not confirmed output, conflict may be
						// possible
						// check the time, if the output is stale
						return false;
					}
				}
			}
		}
		return true;
	}

	/*
	 * spendpending has timeout for 5 minute return false, if there is spendpending
	 * and timeout not
	 */
	public boolean checkSpendpending(UTXO output) {
		int SPENTPENDINGTIMEOUT = 300000;
		if (output.isSpendPending()) {
			return (System.currentTimeMillis() - output.getSpendPendingTime()) > SPENTPENDINGTIMEOUT;
		}
		return true;

	}
}
