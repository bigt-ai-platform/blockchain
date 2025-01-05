/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.base;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventCancel;
import net.bigtangle.core.ContractEventCancelInfo;
import net.bigtangle.core.ContractEventInfo;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.Contractresult;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderCancel;
import net.bigtangle.core.OrderCancelInfo;
import net.bigtangle.core.OrderOpenInfo;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Orderresult;
import net.bigtangle.core.OutputsMulti;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Side;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.response.AbstractResponse;
import net.bigtangle.core.response.GetBlockEvaluationsResponse;
import net.bigtangle.script.Script;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.data.OrderExecutionResult;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;

public class ServiceBaseConnect extends ServiceBaseConfirmation {

	private static final Logger logger = LoggerFactory.getLogger(ServiceBaseConnect.class);

	public ServiceBaseConnect(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService, ObjectMapper jsonmapper) {
		super(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);

	}

	/*
	 * failed blocks without conflict for retry
	 */
	public AbstractResponse findRetryBlocks(Map<String, Object> request, BlockStoreInterface store)
			throws BlockStoreException {
		@SuppressWarnings("unchecked")
		List<String> address = (List<String>) request.get("address");
		String lastestAmount = request.get("lastestAmount") == null ? "0" : request.get("lastestAmount").toString();
		long height = request.get("height") == null ? 0L : Long.parseLong(request.get("height").toString());
		List<BlockEvaluationDisplay> evaluations = store.getSearchBlockEvaluations(address, lastestAmount, height,
				serverConfiguration.getMaxsearchblocks());
		return GetBlockEvaluationsResponse.create(evaluations);
	}

	public static class RewardBuilderResult {
		Transaction tx;
		long difficulty;

		public RewardBuilderResult(Transaction tx, long difficulty) {
			this.tx = tx;
			this.difficulty = difficulty;
		}

		public Transaction getTx() {
			return tx;
		}

		public long getDifficulty() {
			return difficulty;
		}
	}

	/**
	 * Locates the point in the chain at which newBlock and chainHead diverge.
	 * Returns null if no split point was found (ie they are not part of the same
	 * chain). Returns newChainHead or chainHead if they don't actually diverge but
	 * are part of the same chain. return null, if the newChainHead is not complete
	 * locally.
	 */
	public Block findSplit(Block newChainHead, Block oldChainHead, BlockStoreInterface store)
			throws BlockStoreException {
		Block currentChainCursor = oldChainHead;
		Block newChainCursor = newChainHead;
		// Loop until we find the reward block both chains have in common.
		// Example:
		//
		// A -> B -> C -> D
		// *****\--> E -> F -> G
		//
		// findSplit will return block B. oldChainHead = D and newChainHead = G.
		while (!currentChainCursor.equals(newChainCursor)) {
			if (getRewardInfo(currentChainCursor).getChainlength() > getRewardInfo(newChainCursor).getChainlength()) {
				currentChainCursor = store.get(getRewardInfo(currentChainCursor).getPrevRewardHash());
				checkNotNull(currentChainCursor, "Attempt to follow an orphan chain");

			} else {
				newChainCursor = store.get(getRewardInfo(newChainCursor).getPrevRewardHash());
				checkNotNull(newChainCursor, "Attempt to follow an orphan chain");

			}
		}
		return currentChainCursor;
	}

	public Block findSplitExecute(Block newChainHead, Block oldChainHead, BlockStoreInterface store)
			throws BlockStoreException {
		Block currentChainCursor = oldChainHead;
		Block newChainCursor = newChainHead;
		// Loop until we find the reward block both chains have in common.
		// Example:
		//
		// A -> B -> C -> D
		// *****\--> E -> F -> G
		//
		// findSplit will return block B. oldChainHead = D and newChainHead = G.
		while (!currentChainCursor.equals(newChainCursor)) {
			if (getExecutionPrev(currentChainCursor, store).getHeight() > getExecutionPrev(newChainCursor, store)
					.getHeight()) {
				currentChainCursor = getExecutionPrev(currentChainCursor, store);
				checkNotNull(currentChainCursor, "Attempt to follow an orphan  execute chain");
			} else {
				newChainCursor = getExecutionPrev(newChainCursor, store);
				checkNotNull(newChainCursor, "Attempt to follow an orphan  execute chain");

			}
		}
		return currentChainCursor;
	}

	@Override
	protected void connectUTXOs(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		List<Transaction> transactions = block.getTransactions();
		connectUTXOs(block, transactions, blockStore);
	}

	@Override
	protected void connectUTXOs(Block block, List<Transaction> transactions, BlockStoreInterface blockStore)
			throws BlockStoreException {
		for (final Transaction tx : transactions) {
			boolean isCoinBase = tx.isCoinBase();
			List<UTXO> utxos = new ArrayList<>();
			for (TransactionOutput out : tx.getOutputs()) {
				Script script = getScript(out.getScriptBytes());
				String fromAddress = fromAddress(tx, isCoinBase);
				int minsignnumber = 1;
				if (script.isSentToMultiSig()) {
					minsignnumber = script.getNumberOfSignaturesRequiredToSpend();
				}
				UTXO newOut = new UTXO(tx.getHash(), out.getIndex(), out.getValue(), isCoinBase, script,
						getScriptAddress(script), block.getHash(), fromAddress, tx.getMemo(),
						Utils.HEX.encode(out.getValue().getTokenid()), false, false, false, minsignnumber, 0,
						block.getTimeSeconds(), null);

				if (!newOut.isZero()) {
					// logger.debug(newOut.toString());
					utxos.add(newOut);
					if (script.isSentToMultiSig()) {

						for (ECKey ecKey : script.getPubKeys()) {
							String toaddress = ecKey.toAddress(networkParameters).toBase58();
							OutputsMulti outputsMulti = new OutputsMulti(newOut.getTxHash(), toaddress,
									newOut.getIndex());
							blockStore.insertOutputsMulti(outputsMulti);
						}
					}
				}

			}
			blockStore.addUnspentTransactionOutput(utxos);
			for (UTXO u : utxos)
				cacheBlockService.evictTransactionOutput(u, blockStore);
		}
	}

	public void connectTypeSpecificUTXOs(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		switch (block.getBlockType()) {

		case BLOCKTYPE_TOKEN_CREATION:
			connectToken(block, blockStore);
			break;

		case BLOCKTYPE_CONTRACT_EXECUTE:
			connectContractExecute(block, blockStore);
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			connectOrderExecute(block, blockStore);
			break;
		case BLOCKTYPE_ORDER_OPEN:
			connectOrder(block, blockStore);
			break;
		case BLOCKTYPE_ORDER_CANCEL:
			connectCancelOrder(block, blockStore);
			break;
		case BLOCKTYPE_CONTRACTEVENT_CANCEL:
			connectContractEventCancel(block, blockStore);
			break;
		case BLOCKTYPE_CONTRACT_EVENT:
			connectContractEvent(block, blockStore);
		default:
			break;

		}
	}

	private void connectCancelOrder(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		try {
			OrderCancelInfo info = new OrderCancelInfo().parse(block.getTransactions().get(0).getData());
			OrderCancel record = new OrderCancel(info.getBlockHash());
			record.setBlockHash(block.getHash());
			blockStore.insertCancelOrder(record);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void connectContractEventCancel(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		try {
			ContractEventCancelInfo info = new ContractEventCancelInfo()
					.parse(block.getTransactions().get(0).getData());
			ContractEventCancel record = new ContractEventCancel(info.getBlockHash());
			record.setBlockHash(block.getHash());
			blockStore.insertContractEventCancel(record);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void connectOrder(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		try {
			OrderOpenInfo reqInfo = new OrderOpenInfo().parse(block.getTransactions().get(0).getData());
			// calculate the offervalue for version == 1
			if (reqInfo.getVersion() == 1) {
				Coin burned = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
						.countBurnedToken(block, blockStore);
				reqInfo.setOfferValue(burned.getValue().longValue());
				reqInfo.setOfferTokenid(burned.getTokenHex());
			}
			boolean buy = reqInfo.buy();
			Side side = buy ? Side.BUY : Side.SELL;
			int decimals;
			if (buy) {
				decimals = blockStore.getTokenID(reqInfo.getTargetTokenid()).get(0).getDecimals();
			} else {
				decimals = blockStore.getTokenID(reqInfo.getOfferTokenid()).get(0).getDecimals();
			}
			OrderRecord record = new OrderRecord(block.getHash(), Sha256Hash.ZERO_HASH, reqInfo.getOfferValue(),
					reqInfo.getOfferTokenid(), false, false, null, reqInfo.getTargetValue(), reqInfo.getTargetTokenid(),
					reqInfo.getBeneficiaryPubKey(), reqInfo.getValidToTime(), reqInfo.getValidFromTime(), side.name(),
					reqInfo.getBeneficiaryAddress(), reqInfo.getOrderBaseToken(), reqInfo.getPrice(), decimals);
			versionPrice(record, reqInfo);
			List<OrderRecord> orders = new ArrayList<>();
			orders.add(record);
			blockStore.insertOrder(orders);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void connectContractEvent(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		try {
			ContractEventInfo reqInfo = new ContractEventInfo().parse(block.getTransactions().get(0).getData());

			ContractEventRecord record = new ContractEventRecord(block.getHash(), Sha256Hash.ZERO_HASH,
					reqInfo.getContractTokenid(), false, false, null, reqInfo.getOfferValue(),
					reqInfo.getOfferTokenid(), reqInfo.getBeneficiaryAddress());
			List<ContractEventRecord> events = new ArrayList<>();
			events.add(record);
			blockStore.insertContractEvent(events);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void connectContractExecute(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		try {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(block.getTransactions().get(0).getData());
			Contractresult prevblockhash = blockStore.getContractresult(result.getPrevblockhash());
			ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService,jsonmapper).executeContract(block, blockStore, result.getContracttokenid(), prevblockhash,
							result.getReferencedBlocks());
			// check.getOutputTx().getOutput(0).getScriptPubKey().getToAddress(networkParameters);

			if (check != null && result.getOutputTxHash().equals(check.getOutputTxHash())
					&& result.getToBeSpent().equals(check.getToBeSpent())
					&& result.getRemainderRecords().equals(check.getRemainderRecords())
					&& result.getCancelRecords().equals(check.getCancelRecords())) {

				blockStore.insertContractResult(result, block.getHash());
				insertVirtualUTXOs(block, check.getOutputTx(), blockStore);
				for (ContractEventRecord c : check.getRemainderContractEventRecord()) {
					// connected not confirmed
					c.setConfirmed(false);
					c.setCollectinghash(block.getHash());
				}
				blockStore.insertContractEvent(check.getRemainderContractEventRecord());

			} else {
				// the ContractExecute can not be reproduced here
				logger.debug("ContractResult check failed  from result {} compare to check {}", result,
						check==null?"":check.toString());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void connectOrderExecute(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		try {
			OrderExecutionResult result = new OrderExecutionResult().parse(block.getTransactions().get(0).getData());
			Orderresult prevblockhash = blockStore.getOrderResult(result.getPrevblockhash());
			OrderExecutionResult check = new ServiceOrderExecution(serverConfiguration, networkParameters, cacheBlockService,jsonmapper).orderMatching(block, prevblockhash, result.getReferencedBlocks(), blockStore);

			if (check != null && result.getOutputTxHash().equals(check.getOutputTxHash())
					&& result.getToBeSpent().equals(check.getToBeSpent())
					&& result.getRemainderRecords().equals(check.getRemainderRecords())
					&& result.getCancelRecords().equals(check.getCancelRecords())) {
				blockStore.insertOrderResult(result, block.getHash());
				insertVirtualUTXOs(block, check.getOutputTx(), blockStore);

				for (OrderRecord c : check.getRemainderOrderRecord()) {
					c.setIssuingMatcherBlockHash(block.getHash());
					c.setConfirmed(false);
				}
				blockStore.insertOrder(check.getRemainderOrderRecord());

			} else {
				// the ContractExecute can not be reproduced here
				logger.warn("OrderExecutionResult check failed  from result {} compare to check {}", result,
						check==null?"":check.toString());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void connectToken(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		Transaction tx = block.getTransactions().get(0);
		if (tx.getData() != null) {
			try {
				byte[] buf = tx.getData();
				TokenInfo tokenInfo = new TokenInfo().parse(buf);

				// Correctly insert tokens
				tokenInfo.getToken().setConfirmed(false);
				tokenInfo.getToken().setBlockHash(block.getHash());

				blockStore.insertToken(block.getHash(), tokenInfo.getToken());

				// Correctly insert additional data
				for (MultiSignAddress permissionedAddress : tokenInfo.getMultiSignAddresses()) {
					if (permissionedAddress == null)
						continue;
					// The primary key must be the correct block
					permissionedAddress.setBlockhash(block.getHash());
					permissionedAddress.setTokenid(tokenInfo.getToken().getTokenid());
					if (permissionedAddress.getAddress() != null)
						blockStore.insertMultiSignAddress(permissionedAddress);
				}
			} catch (IOException e) {

				throw new RuntimeException(e);
			}

		}
	}



	public static class SortbyBlock implements Comparator<Block> {

		public int compare(Block a, Block b) {
			return a.getHeight() <= b.getHeight() ? 1 : -1;
		}
	}

	public static class SortbyBlockWrap implements Comparator<BlockWrap> {

		public int compare(BlockWrap a, BlockWrap b) {
			return a.getBlock().getHeight() <= b.getBlock().getHeight() ? 1 : -1;
		}
	}

	public static class SortbyBlockWrapAsc implements Comparator<BlockWrap> {

		public int compare(BlockWrap a, BlockWrap b) {
			return a.getBlock().getHeight() >= b.getBlock().getHeight() ? 1 : -1;
		}
	}

}
