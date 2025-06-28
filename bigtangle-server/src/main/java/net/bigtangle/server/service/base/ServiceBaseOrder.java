/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.base;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.math.LongMath;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderCancelInfo;
import net.bigtangle.core.OrderExecutionResult;
import net.bigtangle.core.OrderOpenInfo;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Side;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException.InvalidTransactionDataException;
import net.bigtangle.exception.VerificationException.MissingDependencyException;
import net.bigtangle.ordermatch.MatchResult;
import net.bigtangle.ordermatch.OrderBookEvents;
import net.bigtangle.ordermatch.TradePair;
import net.bigtangle.ordermatch.OrderBookEvents.Event;
import net.bigtangle.ordermatch.OrderBookEvents.Match;
import net.bigtangle.script.Script;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.OrderMatchingResult;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.utils.OrderBook;
import net.bigtangle.store.BlockStoreInterface;

public abstract class ServiceBaseOrder extends ServiceBase {

	private static final Logger logger = LoggerFactory.getLogger(ServiceBaseOrder.class);

	public ServiceBaseOrder(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService, ObjectMapper jsonmapper) {
		super(serverConfiguration, networkParameters, cacheBlockService,jsonmapper);

	} 

	public void addMatchingEvents(OrderMatchingResult orderMatchingResult, String transactionHash, long matchBlockTime,
			BlockStoreInterface store) throws BlockStoreException {
		// collect the spend order volumes and ticker to write to database
		List<MatchResult> matchResultList = new ArrayList<MatchResult>();
		try {
			for (Entry<TradePair, List<Event>> entry : orderMatchingResult.getTokenId2Events().entrySet()) {
				for (Event event : entry.getValue()) {
					if (event instanceof Match) {
						MatchResult f = new MatchResult(transactionHash, entry.getKey().getOrderToken(),
								entry.getKey().getOrderBaseToken(), ((OrderBookEvents.Match) event).price,
								((OrderBookEvents.Match) event).executedQuantity, matchBlockTime);
						matchResultList.add(f);

					}
				}
			}
			if (!matchResultList.isEmpty())
				store.insertMatchingEvent(matchResultList);

		} catch (Exception e) {
			// this is analysis data and is not consensus relevant
		}
	}

	public void addMatchingEventsOrderExecution(OrderExecutionResult orderExecutionResult, String transactionHash,
			long matchBlockTime, BlockStoreInterface store) throws BlockStoreException {
		// collect the spend order volumes and ticker to write to database
		List<MatchResult> matchResultList = new ArrayList<MatchResult>();
		try {
			for (Entry<TradePair, List<Event>> entry : orderExecutionResult.getTokenId2Events().entrySet()) {
				for (Event event : entry.getValue()) {
					if (event instanceof Match) {
						MatchResult f = new MatchResult(transactionHash, entry.getKey().getOrderToken(),
								entry.getKey().getOrderBaseToken(), ((OrderBookEvents.Match) event).price,
								((OrderBookEvents.Match) event).executedQuantity, matchBlockTime);
						matchResultList.add(f);

					}
				}
			}
			if (!matchResultList.isEmpty())
				store.insertMatchingEvent(matchResultList);

		} catch (Exception e) {
			// this is analysis data and is not consensus relevant
		}
	}

	public boolean spentInRemainder(OrderRecord c, Collection<OrderRecord> remainderRecords)
			throws BlockStoreException, IOException {
		for (OrderRecord r : remainderRecords) {
			if (r.getBlockHash().equals(c.getBlockHash()))
				return true;
		}
		return false;
	}

	public void debugOrderExecutionResult(Block block, OrderExecutionResult check, boolean confirm,
			BlockStoreInterface blockStore) throws BlockStoreException, IOException {

		for (BlockWrap c : getReferrencedBlockWrap(block, blockStore)) {
			logger.debug("getReferrencedBlockWrap +  confirm =" + confirm);
			logger.debug(c.toString());
		}

	 
		for (OrderRecord c : check.getRemainderOrderRecord()) {
			logger.debug("getRemainderOrderRecord +  confirm =" + confirm);
			logger.debug(c.toString());
		}

	}

	/*
	 * price is in version 1 not in OrderOpenInfo
	 */
	/*
	 * Buy order is defined by given targetValue=buy amount, targetToken=buytoken
	 * 
	 * offervalue = targetValue * price / 10**targetDecimal price= offervalue *
	 * 10**targetDecimal/targetValue offerToken=orderBaseToken
	 * 
	 */
	/*
	 * Sell Order is defined by given offerValue= sell amount, offentoken=sellToken
	 * 
	 * targetvalue = offervalue * price / 10**offerDecimal
	 * targetToken=orderBaseToken
	 */
	public void versionPrice(OrderRecord record, OrderOpenInfo reqInfo) {
		if (reqInfo.getVersion() == 1) {
			boolean buy = record.getOfferTokenid().equals(NetworkParameters.BIGTANGLE_TOKENID_STRING);
			if (buy) {
				record.setPrice(calc(record.getOfferValue(), LongMath.checkedPow(10, record.getTokenDecimals()),
						record.getTargetValue()));
			} else {
				record.setPrice(calc(record.getTargetValue(), LongMath.checkedPow(10, record.getTokenDecimals()),
						record.getOfferValue()));
			}
		}
	}

	public Long calc(long m, long factor, long d) {
		return BigInteger.valueOf(m).multiply(BigInteger.valueOf(factor)).divide(BigInteger.valueOf(d)).longValue();
	}

	protected void insertIntoOrderBooks(OrderRecord o, TreeMap<TradePair, OrderBook> orderBooks,
			ArrayList<OrderRecord> orderId2Order, long orderId, BlockStoreInterface blockStore) throws BlockStoreException {

		Side side = o.getSide();
		// must be in in base unit ;

		long price = o.getPrice();
		if (price <= 0)
			logger.warn(" price is wrong " + price);
		// throw new RuntimeException(" price is wrong " +price);
		String tradetokenId = o.getOfferTokenid().equals(o.getOrderBaseToken()) ? o.getTargetTokenid()
				: o.getOfferTokenid();

		long size = o.getOfferTokenid().equals(o.getOrderBaseToken()) ? o.getTargetValue() : o.getOfferValue();

		TradePair tokenPaar = new TradePair(tradetokenId, o.getOrderBaseToken());

		OrderBook orderBook = orderBooks.get(tokenPaar);
		if (orderBook == null) {
			orderBook = new OrderBook(new OrderBookEvents());
			orderBooks.put(tokenPaar, orderBook);
		}
		orderId2Order.add(o);
		orderBook.enter(orderId, side, price, size);
	}

	/**
	 * Deterministically execute the order matching algorithm on this block.
	 * 
	 * @return new consumed orders, virtual order matching tx and newly generated
	 *         remaining MODIFIED order book
	 * @throws BlockStoreException
	 */
	public OrderMatchingResult generateOrderMatching(Block rewardBlock, BlockStoreInterface blockStore)
			throws BlockStoreException {

		RewardInfo rewardInfo = new RewardInfo().parseChecked(rewardBlock.getTransactions().get(0).getData());

		return generateOrderMatching(rewardBlock, rewardInfo, blockStore);
	}

	public OrderMatchingResult generateOrderMatching(Block block, RewardInfo rewardInfo, BlockStoreInterface blockStore)
			throws BlockStoreException {
		TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts = new TreeMap<>();

		// Get previous order matching block
		Sha256Hash prevHash = rewardInfo.getPrevRewardHash();
		Set<Sha256Hash> collectedBlocks = rewardInfo.getBlocks();
		final Block prevMatchingBlock = getBlock(prevHash, blockStore);

		// Deterministic randomization
		byte[] randomness = Utils.xor(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes());

		// Collect all orders approved by this block in the interval
		List<OrderCancelInfo> cancels = new ArrayList<>();
		Map<Sha256Hash, OrderRecord> sortedNewOrders = new TreeMap<>(
				Comparator.comparing(hash -> Sha256Hash.wrap(Utils.xor(((Sha256Hash) hash).getBytes(), randomness))));
		HashMap<Sha256Hash, OrderRecord> remainingOrders = blockStore.getOrderMatchingIssuedOrders(prevHash);
		Set<OrderRecord> toBeSpentOrders = new HashSet<>();
		Set<OrderRecord> cancelledOrders = new HashSet<>();
		for (OrderRecord r : remainingOrders.values()) {
			toBeSpentOrders.add(OrderRecord.cloneOrderRecord(r));
		}
		collectOrdersWithCancel(block, collectedBlocks, cancels, sortedNewOrders, toBeSpentOrders, blockStore);
		// sort order for execute in deterministic randomness
		Map<Sha256Hash, OrderRecord> sortedOldOrders = new TreeMap<>(
				Comparator.comparing(hash -> Sha256Hash.wrap(Utils.xor(((Sha256Hash) hash).getBytes(), randomness))));
		sortedOldOrders.putAll(remainingOrders);
		remainingOrders.putAll(sortedNewOrders);

		// Issue timeout cancels, set issuing order blockhash
		setIssuingBlockHash(block, remainingOrders);
		timeoutOrdersToCancelled(block, remainingOrders, cancelledOrders);
		cancelOrderstoCancelled(cancels, remainingOrders, cancelledOrders);

		// Remove the now cancelled orders from rest of orders
		for (OrderRecord c : cancelledOrders) {
			remainingOrders.remove(c.getBlockHash());
			sortedOldOrders.remove(c.getBlockHash());
			sortedNewOrders.remove(c.getBlockHash());
		}

		// Add to proceeds all cancelled orders going back to the beneficiary
		payoutCancelledOrders(payouts, cancelledOrders);

		// From all orders and ops, begin order matching algorithm by filling
		// order books
		int orderId = 0;
		ArrayList<OrderRecord> orderId2Order = new ArrayList<>();
		TreeMap<TradePair, OrderBook> orderBooks = new TreeMap<TradePair, OrderBook>();

		// Add old orders first without not valid yet
		for (OrderRecord o : sortedOldOrders.values()) {
			if (o.isValidYet(block.getTimeSeconds()) && o.isValidYet(prevMatchingBlock.getTimeSeconds()))
				insertIntoOrderBooks(o, orderBooks, orderId2Order, orderId++, blockStore);
		}

		// Now orders not valid before but valid now
		for (OrderRecord o : sortedOldOrders.values()) {
			if (o.isValidYet(block.getTimeSeconds()) && !o.isValidYet(prevMatchingBlock.getTimeSeconds()))
				insertIntoOrderBooks(o, orderBooks, orderId2Order, orderId++, blockStore);
		}

		// Now new orders that are valid yet
		for (OrderRecord o : sortedNewOrders.values()) {
			if (o.isValidYet(block.getTimeSeconds()))
				insertIntoOrderBooks(o, orderBooks, orderId2Order, orderId++, blockStore);
		}

		// Collect and process all matching events
		Map<TradePair, List<Event>> tokenId2Events = new HashMap<>();
		for (Entry<TradePair, OrderBook> orderBook : orderBooks.entrySet()) {
			processOrderBook(payouts, remainingOrders, orderId2Order, tokenId2Events, orderBook);
		}

		for (OrderRecord o : remainingOrders.values()) {
			o.setDefault();
		}

		// Make deterministic tx with proceeds
		Transaction tx = createOrderPayoutTransaction(block, payouts);
		// logger.debug(tx.toString());
		return new OrderMatchingResult(toBeSpentOrders, tx, remainingOrders.values(), tokenId2Events);
	}

	protected void collectOrdersWithCancel(Block block, Set<Sha256Hash> collectedBlocks, List<OrderCancelInfo> cancels,
			Map<Sha256Hash, OrderRecord> newOrders, Set<OrderRecord> toBeSpentOrders, BlockStoreInterface blockStore)
			throws BlockStoreException {
		for (Sha256Hash bHash : collectedBlocks) {
			BlockWrap b = getBlockWrap(bHash, blockStore);
			if (b == null)
				throw new MissingDependencyException();
			if (b.getBlock().getBlockType() == BlockType.BLOCKTYPE_ORDER_OPEN) {

				OrderRecord order = blockStore.getOrder(b.getBlock().getHash(), Sha256Hash.ZERO_HASH);
				// order is null, write it to
				if (order == null) {
					connectUTXOs(b.getBlock(), blockStore);
					connectTypeSpecificUTXOs(b.getBlock(), blockStore);
					order = blockStore.getOrder(b.getBlock().getHash(), Sha256Hash.ZERO_HASH);
				}
				if (order != null) {
					newOrders.put(b.getBlock().getHash(), OrderRecord.cloneOrderRecord(order));
					toBeSpentOrders.add(order);
				}
			} else if (b.getBlock().getBlockType() == BlockType.BLOCKTYPE_ORDER_CANCEL) {
				OrderCancelInfo info = new OrderCancelInfo()
						.parseChecked(b.getBlock().getTransactions().get(0).getData());
				cancels.add(info);
			}
		}
	}

	public Set<Sha256Hash> getOrderRecordHash(Set<OrderRecord> orders) {
		Set<Sha256Hash> hashs = new HashSet<>();
		for (OrderRecord o : orders) {
			hashs.add(o.getBlockHash());
		}
		return hashs;
	}

	public Transaction createOrderPayoutTransaction(Block block,
			TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts) {
		Transaction tx = new Transaction(networkParameters);
		for (Entry<ByteBuffer, TreeMap<String, BigInteger>> payout : payouts.entrySet()) {
			byte[] beneficiaryPubKey = payout.getKey().array();

			for (Entry<String, BigInteger> tokenProceeds : payout.getValue().entrySet()) {
				String tokenId = tokenProceeds.getKey();
				BigInteger proceedsValue = tokenProceeds.getValue();

				if (proceedsValue.signum() != 0)
					tx.addOutput(new Coin(proceedsValue, tokenId), ECKey.fromPublicOnly(beneficiaryPubKey));
			}
		}

		// The coinbase input does not really need to be a valid signature
		TransactionInput input = new TransactionInput(networkParameters, tx, Script
				.createInputScript(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes()));
		tx.addInput(input);
		tx.setMemo(new MemoInfo("Order Payout"));

		return tx;
	}

	protected void processOrderBook(TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts,
			HashMap<Sha256Hash, OrderRecord> remainingOrders, ArrayList<OrderRecord> orderId2Order,
			Map<TradePair, List<Event>> tokenId2Events, Entry<TradePair, OrderBook> orderBook)
			throws BlockStoreException {
		String orderBaseToken = orderBook.getKey().getOrderBaseToken();

		List<Event> events = ((OrderBookEvents) orderBook.getValue().listener()).collect();

		for (Event event : events) {
			if (!(event instanceof Match))
				continue;

			Match matchEvent = (Match) event;
			OrderRecord restingOrder = orderId2Order.get(Integer.parseInt(matchEvent.restingOrderId));
			OrderRecord incomingOrder = orderId2Order.get(Integer.parseInt(matchEvent.incomingOrderId));
			byte[] restingPubKey = restingOrder.getBeneficiaryPubKey();
			byte[] incomingPubKey = incomingOrder.getBeneficiaryPubKey();

			// Now disburse proceeds accordingly
			long executedPrice = matchEvent.price;
			long executedAmount = matchEvent.executedQuantity;
			// if
			// (!orderBook.getKey().getOrderToken().equals(incomingOrder.getTargetTokenid()))

			if (matchEvent.incomingSide == Side.BUY) {
				processIncomingBuy(payouts, remainingOrders, orderBaseToken, restingOrder, incomingOrder, restingPubKey,
						incomingPubKey, executedPrice, executedAmount);
			} else {
				processIncomingSell(payouts, remainingOrders, orderBaseToken, restingOrder, incomingOrder,
						restingPubKey, incomingPubKey, executedPrice, executedAmount);

			}
		}
		tokenId2Events.put(orderBook.getKey(), events);
	}

	private void processIncomingSell(TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts,
			HashMap<Sha256Hash, OrderRecord> remainingOrders, String baseToken, OrderRecord restingOrder,
			OrderRecord incomingOrder, byte[] restingPubKey, byte[] incomingPubKey, long executedPrice,
			long executedAmount) {
		long sellableAmount = incomingOrder.getOfferValue();
		long buyableAmount = restingOrder.getTargetValue();
		long incomingPrice = incomingOrder.getPrice();
		Integer priceshift = networkParameters.getOrderPriceShift(baseToken);
		// The resting order receives the tokens
		payout(payouts, restingPubKey, restingOrder.getTargetTokenid(), executedAmount);

		// The incoming order receives the base token according to the
		// resting price
		payout(payouts, incomingPubKey, baseToken,
				totalAmount(executedAmount, executedPrice, incomingOrder.getTokenDecimals() + priceshift));

		// Finally, the orders could be fulfilled now, so we can
		// remove them from the order list
		// Otherwise, we will make the orders smaller by the
		// executed amounts
		incomingOrder.setOfferValue(incomingOrder.getOfferValue() - executedAmount);
		incomingOrder.setTargetValue(incomingOrder.getTargetValue()
				- totalAmount(executedAmount, incomingPrice, incomingOrder.getTokenDecimals() + priceshift));
		restingOrder.setOfferValue(restingOrder.getOfferValue()
				- totalAmount(executedAmount, executedPrice, restingOrder.getTokenDecimals() + priceshift));
		restingOrder.setTargetValue(restingOrder.getTargetValue() - executedAmount);
		if (sellableAmount == executedAmount) {
			remainingOrders.remove(incomingOrder.getBlockHash());
		}
		if (buyableAmount == executedAmount) {
			remainingOrders.remove(restingOrder.getBlockHash());
		}
	}

	private void processIncomingBuy(TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts,
			HashMap<Sha256Hash, OrderRecord> remainingOrders, String baseToken, OrderRecord restingOrder,
			OrderRecord incomingOrder, byte[] restingPubKey, byte[] incomingPubKey, long executedPrice,
			long executedAmount) {
		long sellableAmount = restingOrder.getOfferValue();
		long buyableAmount = incomingOrder.getTargetValue();
		long incomingPrice = incomingOrder.getPrice();

		// The resting order receives the basetoken according to its price
		// resting is sell order
		Integer priceshift = networkParameters.getOrderPriceShift(baseToken);
		payout(payouts, restingPubKey, baseToken,
				totalAmount(executedAmount, executedPrice, restingOrder.getTokenDecimals() + priceshift));

		// The incoming order receives the tokens
		payout(payouts, incomingPubKey, incomingOrder.getTargetTokenid(), executedAmount);

		// The difference in price is returned to the incoming
		// beneficiary
		payout(payouts, incomingPubKey, baseToken, totalAmount(executedAmount, (incomingPrice - executedPrice),
				incomingOrder.getTokenDecimals() + priceshift));

		// Finally, the orders could be fulfilled now, so we can
		// remove them from the order list
		restingOrder.setOfferValue(restingOrder.getOfferValue() - executedAmount);
		restingOrder.setTargetValue(restingOrder.getTargetValue()
				- totalAmount(executedAmount, executedPrice, restingOrder.getTokenDecimals() + priceshift));
		incomingOrder.setOfferValue(incomingOrder.getOfferValue()
				- totalAmount(executedAmount, incomingPrice, incomingOrder.getTokenDecimals() + priceshift));
		incomingOrder.setTargetValue(incomingOrder.getTargetValue() - executedAmount);
		if (sellableAmount == executedAmount) {
			remainingOrders.remove(restingOrder.getBlockHash());
		}
		if (buyableAmount == executedAmount) {
			remainingOrders.remove(incomingOrder.getBlockHash());
		}
	}

	/*
	 * It must use BigInteger to calculation to avoid overflow. Order can handle
	 * only Long
	 */
	public Long totalAmount(long price, long amount, int tokenDecimal) {

		BigInteger[] rearray = BigInteger.valueOf(price).multiply(BigInteger.valueOf(amount))
				.divideAndRemainder(BigInteger.valueOf(LongMath.checkedPow(10, tokenDecimal)));
		BigInteger re = rearray[0];
		BigInteger remainder = rearray[1];
		if (remainder.compareTo(BigInteger.ZERO) > 0) {
			// This remainder will cut
			// logger.debug("Price and quantity value with remainder " + remainder
			// + "/"
			// + BigInteger.valueOf(LongMath.checkedPow(10, tokenDecimal)));
		}

		if (re.compareTo(BigInteger.ZERO) < 0 || re.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
			throw new InvalidTransactionDataException("Invalid target total value: " + re);
		}
		return re.longValue();
	}

	protected void payoutCancelledOrders(TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts,
			Set<OrderRecord> cancelledOrders) {
		for (OrderRecord o : cancelledOrders) {
			byte[] beneficiaryPubKey = o.getBeneficiaryPubKey();
			String offerTokenid = o.getOfferTokenid();
			long offerValue = o.getOfferValue();

			payout(payouts, beneficiaryPubKey, offerTokenid, offerValue);
		}
	}

	protected void payout(TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts, byte[] beneficiaryPubKey,
			String tokenid, long tokenValue) {
		TreeMap<String, BigInteger> proceeds = payouts.get(ByteBuffer.wrap(beneficiaryPubKey));
		if (proceeds == null) {
			proceeds = new TreeMap<>();
			payouts.put(ByteBuffer.wrap(beneficiaryPubKey), proceeds);
		}
		BigInteger offerTokenProceeds = proceeds.get(tokenid);
		if (offerTokenProceeds == null) {
			offerTokenProceeds = BigInteger.ZERO;
			proceeds.put(tokenid, offerTokenProceeds);
		}
		proceeds.put(tokenid, offerTokenProceeds.add(BigInteger.valueOf(tokenValue)));
	}

	protected void cancelOrderstoCancelled(List<OrderCancelInfo> cancels,
			HashMap<Sha256Hash, OrderRecord> remainingOrders, Set<OrderRecord> cancelledOrders) {
		for (OrderCancelInfo c : cancels) {
			if (remainingOrders.containsKey(c.getBlockHash())) {
				cancelledOrders.add(remainingOrders.get(c.getBlockHash()));
			}
		}
	}

	protected void setIssuingBlockHash(Block block, HashMap<Sha256Hash, OrderRecord> remainingOrders) {
		Iterator<Entry<Sha256Hash, OrderRecord>> it = remainingOrders.entrySet().iterator();
		while (it.hasNext()) {
			OrderRecord order = it.next().getValue();
			order.setIssuingMatcherBlockHash(block.getHash());
		}
	}

	protected void timeoutOrdersToCancelled(Block block, HashMap<Sha256Hash, OrderRecord> remainingOrders,
			Set<OrderRecord> cancelledOrders) {
		// Issue timeout cancels, set issuing order blockhash
		Iterator<Entry<Sha256Hash, OrderRecord>> it = remainingOrders.entrySet().iterator();
		while (it.hasNext()) {
			OrderRecord order = it.next().getValue();
			if (order.isTimeouted(block.getTimeSeconds())) {
				cancelledOrders.add(order);
			}
		}
	}

	protected void insertVirtualOrderRecords(Block block, Collection<OrderRecord> orders, BlockStoreInterface blockStore) {
		try {

			blockStore.insertOrder(orders);

		} catch (BlockStoreException e) {
			// Expected after reorgs
			logger.warn("Probably reinserting orders: ", e);
		}
	}
	public void removeMatchingEvents(Sha256Hash h, BlockStoreInterface store) throws BlockStoreException {
		store.deleteMatchingEvents(h.toString());
	}

	/**
	 * Calculates and inserts any virtual transaction outputs so dependees can
	 * become solid
	 *
	 * @param block
	 * @throws BlockStoreException
	 */
	public void calculateBlockOrderMatchingResult(Block block, BlockStoreInterface blockStore)
			throws BlockStoreException {

		Transaction tx = null;
		OrderMatchingResult matchingResult = null;

		switch (block.getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE:
			break;
		case BLOCKTYPE_FILE:
			break;
		case BLOCKTYPE_GOVERNANCE:
			break;
		case BLOCKTYPE_INITIAL:
			break;
		case BLOCKTYPE_REWARD:
			tx = generateVirtualMiningRewardTX(block, blockStore);
			insertVirtualUTXOs(block, tx, blockStore);

			// Get list of consumed orders, virtual order matching tx and newly
			// generated remaining order book
			if (!enableOrderMatchExecutionChain(block)) {
				matchingResult = generateOrderMatching(block, blockStore);
				tx = matchingResult.getOutputTx();
				insertVirtualUTXOs(block, tx, blockStore);
				insertVirtualOrderRecords(block, matchingResult.getRemainingOrders(), blockStore);
			}
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			break;
		case BLOCKTYPE_TRANSFER:
			break;
		case BLOCKTYPE_USERDATA:
			break;
		case BLOCKTYPE_CONTRACT_EVENT:
			break;
		case BLOCKTYPE_CONTRACT_EXECUTE:
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			break;
		case BLOCKTYPE_ORDER_OPEN:
			break;
		case BLOCKTYPE_ORDER_CANCEL:
			break;
		case BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;
		default:
			throw new RuntimeException("Not Implemented");

		}

		// Return the computation result
	}

}
