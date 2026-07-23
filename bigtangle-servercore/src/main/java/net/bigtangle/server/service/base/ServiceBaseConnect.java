/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.base;

import java.io.IOException;
import java.util.ArrayList;
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
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.OrderCancel;
import net.bigtangle.core.OrderCancelInfo;
import net.bigtangle.core.OrderExecutionResult;
import net.bigtangle.core.OrderOpenInfo;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.OutputsMulti;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Side;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.GetBlockEvaluationsResponse;
import net.bigtangle.script.Script;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.service.base.handler.ContractExecutorRegistry;
import net.bigtangle.server.service.base.handler.OrderExecutorRegistry;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.server.data.Orderresult;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;

public class ServiceBaseConnect extends ServiceBaseConfirmation
		implements net.bigtangle.server.service.base.handler.OrderMatchingSupport {

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

	@Override
	public void connectUTXOs(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		List<Transaction> transactions = block.getTransactions();
		connectUTXOs(block, transactions, blockStore);
	}

	@Override
	protected void connectUTXOs(Block block, List<Transaction> transactions, BlockStoreInterface blockStore)
			throws BlockStoreException {
		List<UTXO> allUtxos = new ArrayList<>();
		for (final Transaction tx : transactions) {
			boolean isCoinBase = tx.isCoinBase();
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
					allUtxos.add(newOut);
					if (script.isSentToMultiSig()) {
						for (PQKey ecKey : script.getPubKeys()) {
							String toaddress = ecKey.toAddress(networkParameters).toBase58();
							OutputsMulti outputsMulti = new OutputsMulti(newOut.getTxHash(), toaddress,
									newOut.getIndex());
							blockStore.insertOutputsMulti(outputsMulti);
						}
					}
				}
			}
		}
		if (!allUtxos.isEmpty()) {
			blockStore.addUnspentTransactionOutput(allUtxos);
			if (!net.bigtangle.store.DatabaseFullBlockStoreBase.isCacheSkipped()) {
				for (UTXO u : allUtxos)
					cacheBlockService.evictTransactionOutput(u, blockStore);
			}
		}
	}

	public void connectTypeSpecificUTXOs(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		// Layer strategy: delegate to a registered handler if present.
		if (handlerFor(block.getBlockType()).isPresent()) {
			SolidityContext ctx = SolidityContext.builder().block(block).store(blockStore).base(this).build();
			handlerFor(block.getBlockType()).get().connect(ctx);
			return;
		}
		// fallback when no handler registered
		switch (block.getBlockType()) {

		case BLOCKTYPE_TOKEN_CREATION:
			connectToken(block, blockStore);
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

	public void connectCancelOrder(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
		try {
			OrderCancelInfo info = new OrderCancelInfo().parse(block.getTransactions().get(0).getData());
			OrderCancel record = new OrderCancel(info.getBlockHash());
			record.setBlockHash(block.getHash());
			blockStore.insertCancelOrder(record);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void connectContractEventCancel(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
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
				Coin burned = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,
						jsonmapper).countBurnedToken(block, blockStore);
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

	public void connectContractEvent(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
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

	public void connectToken(Block block, BlockStoreInterface blockStore) throws BlockStoreException {
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

  
 

}
