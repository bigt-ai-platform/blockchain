package net.bigtangle.layer1.contract;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import com.google.common.math.LongMath;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.OrderCancelInfo;
import net.bigtangle.core.OrderExecutionResult;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Side;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException.InvalidTransactionDataException;
import net.bigtangle.exception.VerificationException.MissingDependencyException;
import net.bigtangle.ordermatch.TradePair;
import net.bigtangle.ordermatch.OrderBookEvents;
import net.bigtangle.ordermatch.OrderBookEvents.Event;
import net.bigtangle.ordermatch.OrderBookEvents.Match;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.Orderresult;
import net.bigtangle.server.service.base.handler.OrderExecutor;
import net.bigtangle.server.service.base.handler.OrderMatchingSupport;
import net.bigtangle.server.utils.OrderBook;
import net.bigtangle.store.BlockStoreInterface;

public class OrderMatchingEngine implements OrderExecutor {

	@Override
	public OrderExecutionResult executeOrderMatching(OrderMatchingSupport support, NetworkParameters networkParameters,
			Block block, Orderresult prev, Set<Sha256Hash> collectedBlocks,
			BlockStoreInterface blockStore) throws BlockStoreException {
		return orderMatching(support, networkParameters, block, prev, collectedBlocks, blockStore);
	}

	public OrderExecutionResult orderMatching(OrderMatchingSupport support, NetworkParameters networkParameters,
			Block block, Orderresult prev, Set<Sha256Hash> collectedBlocks,
			BlockStoreInterface blockStore) throws BlockStoreException {
		TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts = new TreeMap<>();

		// Deterministic randomization
		byte[] randomness = Utils.xor(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes());

		// Collect all orders approved by this block in the interval
		List<OrderCancelInfo> cancels = new ArrayList<>();
		Map<Sha256Hash, OrderRecord> sortedNewOrders = new TreeMap<>(
				Comparator.comparing(hash -> Sha256Hash.wrap(Utils.xor(hash.getBytes(), randomness))));

		HashMap<Sha256Hash, OrderRecord> remainingOrders = new HashMap<>();
		if (!Sha256Hash.ZERO_HASH.equals(prev.getBlockHash())) {
			// new order must be from collectedBlocks, not this write as
			// Sha256Hash.ZERO_HASH in orders
			remainingOrders = blockStore.getOrderMatchingIssuedOrders(prev.getBlockHash());
		}

		Set<OrderRecord> toBeSpentOrders = new HashSet<>();
		Set<OrderRecord> cancelledOrders = new HashSet<>();
		for (OrderRecord r : remainingOrders.values()) {
			toBeSpentOrders.add(OrderRecord.cloneOrderRecord(r));
		}
		collectOrdersWithCancel(support, block, collectedBlocks, cancels, sortedNewOrders, toBeSpentOrders, blockStore);
		// sort order for execute in deterministic randomness
		Map<Sha256Hash, OrderRecord> sortedOldOrders = new TreeMap<>(
				Comparator.comparing(hash -> Sha256Hash.wrap(Utils.xor(hash.getBytes(), randomness))));
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
		TreeMap<TradePair, OrderBook> orderBooks = new TreeMap<>();

		// Add old orders first without not valid yet
		for (OrderRecord o : sortedOldOrders.values()) {
			if (o.isValidYet(block.getTimeSeconds()) && o.isValidYet(block.getTimeSeconds()))
				insertIntoOrderBooks(o, orderBooks, orderId2Order, orderId++, blockStore);
		}

		// Now orders not valid before but valid now
		for (OrderRecord o : sortedOldOrders.values()) {
			if (o.isValidYet(block.getTimeSeconds()) && !o.isValidYet(block.getTimeSeconds()))
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
			processOrderBook(networkParameters, payouts, remainingOrders, orderId2Order, tokenId2Events, orderBook);
		}

		for (OrderRecord o : remainingOrders.values()) {
			o.setDefault();
		}

		// Make deterministic tx with proceeds
		Transaction tx = createOrderPayoutTransaction(networkParameters, block, payouts);

		return new OrderExecutionResult(tx.getHash(), tx,
				prev.getBlockHash(), getOrderRecordHash(cancelledOrders),
				remainingOrders.keySet(), block.getTimeSeconds(), remainingOrders.values(),
				collectedBlocks, tokenId2Events, prev.getChainlength() + 1);

	}

	private void collectOrdersWithCancel(OrderMatchingSupport support, Block block, Set<Sha256Hash> collectedBlocks,
			List<OrderCancelInfo> cancels, Map<Sha256Hash, OrderRecord> newOrders,
			Set<OrderRecord> toBeSpentOrders, BlockStoreInterface blockStore) throws BlockStoreException {
		for (Sha256Hash bHash : collectedBlocks) {
			BlockWrap b = support.getBlockWrap(bHash, blockStore);
			if (b == null)
				throw new MissingDependencyException();
			if (b.getBlock().getBlockType() == BlockType.BLOCKTYPE_ORDER_OPEN) {

				OrderRecord order = blockStore.getOrder(b.getBlock().getHash(), Sha256Hash.ZERO_HASH);
				// order is null, write it to
				if (order == null) {
					support.connectUTXOs(b.getBlock(), blockStore);
					support.connectTypeSpecificUTXOs(b.getBlock(), blockStore);
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

	private Set<Sha256Hash> getOrderRecordHash(Set<OrderRecord> orders) {
		Set<Sha256Hash> hashs = new HashSet<>();
		for (OrderRecord o : orders) {
			hashs.add(o.getBlockHash());
		}
		return hashs;
	}

	private Transaction createOrderPayoutTransaction(NetworkParameters networkParameters, Block block,
			TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts) {
		Transaction tx = new Transaction(networkParameters);
		for (Entry<ByteBuffer, TreeMap<String, BigInteger>> payout : payouts.entrySet()) {
			byte[] beneficiaryPubKey = payout.getKey().array();

			for (Entry<String, BigInteger> tokenProceeds : payout.getValue().entrySet()) {
				String tokenId = tokenProceeds.getKey();
				BigInteger proceedsValue = tokenProceeds.getValue();

				if (proceedsValue.signum() != 0)
					tx.addOutput(new Coin(proceedsValue, tokenId), PQKey.fromPublicOnly(beneficiaryPubKey));
			}
		}

		// The coinbase input does not really need to be a valid signature
		TransactionInput input = TransactionInput.fromScriptBytes(networkParameters, tx, Script
				.createInputScript(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes()));
		tx.addInput(input);
		tx.setMemo(new MemoInfo("Order Payout"));

		return tx;
	}

	private void processOrderBook(NetworkParameters networkParameters,
			TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts,
			HashMap<Sha256Hash, OrderRecord> remainingOrders, ArrayList<OrderRecord> orderId2Order,
			Map<TradePair, List<Event>> tokenId2Events, Entry<TradePair, OrderBook> orderBook)
			throws BlockStoreException {
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

			if (matchEvent.incomingSide == Side.BUY) {
				processIncomingBuy(networkParameters, orderBook.getKey().getOrderBaseToken(),
						payouts, remainingOrders, restingOrder, incomingOrder, restingPubKey,
						incomingPubKey, executedPrice, executedAmount);
			} else {
				processIncomingSell(networkParameters, orderBook.getKey().getOrderBaseToken(),
						payouts, remainingOrders, restingOrder, incomingOrder,
						restingPubKey, incomingPubKey, executedPrice, executedAmount);

			}
		}
		tokenId2Events.put(orderBook.getKey(), events);
	}

	private void processIncomingSell(NetworkParameters networkParameters, String baseToken,
			TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts,
			HashMap<Sha256Hash, OrderRecord> remainingOrders, OrderRecord restingOrder,
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

	private void processIncomingBuy(NetworkParameters networkParameters, String baseToken,
			TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts,
			HashMap<Sha256Hash, OrderRecord> remainingOrders, OrderRecord restingOrder,
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

	private Long totalAmount(long price, long amount, int tokenDecimal) {

		BigInteger[] rearray = BigInteger.valueOf(price).multiply(BigInteger.valueOf(amount))
				.divideAndRemainder(BigInteger.valueOf(LongMath.checkedPow(10, tokenDecimal)));
		BigInteger re = rearray[0];
		if (re.compareTo(BigInteger.ZERO) < 0 || re.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
			throw new InvalidTransactionDataException("Invalid target total value: " + re);
		}
		return re.longValue();
	}

	private void payoutCancelledOrders(TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts,
			Set<OrderRecord> cancelledOrders) {
		for (OrderRecord o : cancelledOrders) {
			byte[] beneficiaryPubKey = o.getBeneficiaryPubKey();
			String offerTokenid = o.getOfferTokenid();
			long offerValue = o.getOfferValue();

			payout(payouts, beneficiaryPubKey, offerTokenid, offerValue);
		}
	}

	private void payout(TreeMap<ByteBuffer, TreeMap<String, BigInteger>> payouts, byte[] beneficiaryPubKey,
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

	private void cancelOrderstoCancelled(List<OrderCancelInfo> cancels,
			HashMap<Sha256Hash, OrderRecord> remainingOrders, Set<OrderRecord> cancelledOrders) {
		for (OrderCancelInfo c : cancels) {
			if (remainingOrders.containsKey(c.getBlockHash())) {
				cancelledOrders.add(remainingOrders.get(c.getBlockHash()));
			}
		}
	}

	private void setIssuingBlockHash(Block block, HashMap<Sha256Hash, OrderRecord> remainingOrders) {
		Iterator<Entry<Sha256Hash, OrderRecord>> it = remainingOrders.entrySet().iterator();
		while (it.hasNext()) {
			OrderRecord order = it.next().getValue();
			order.setIssuingMatcherBlockHash(block.getHash());
		}
	}

	private void timeoutOrdersToCancelled(Block block, HashMap<Sha256Hash, OrderRecord> remainingOrders,
			Set<OrderRecord> cancelledOrders) {
		Iterator<Entry<Sha256Hash, OrderRecord>> it = remainingOrders.entrySet().iterator();
		while (it.hasNext()) {
			OrderRecord order = it.next().getValue();
			if (order.isTimeouted(block.getTimeSeconds())) {
				cancelledOrders.add(order);
			}
		}
	}

	private void insertIntoOrderBooks(OrderRecord o, TreeMap<TradePair, OrderBook> orderBooks,
			ArrayList<OrderRecord> orderId2Order, long orderId, BlockStoreInterface blockStore) throws BlockStoreException {

		Side side = o.getSide();

		long price = o.getPrice();

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
}
