package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import net.bigtangle.core.OrderCancel;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Token;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.OrderdataResponse;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class OrderdataService {

	public AbstractResponse getOrderdataList(String address, List<String> addresses, String tokenid,
											 BlockStoreInterface store) throws BlockStoreException {
		if (addresses == null)
			addresses = new ArrayList<>();
		if (address != null && !address.isEmpty()) {
			addresses.add(address);
		}

		return getAllOpenOrders(addresses, tokenid, store);

	}

	private AbstractResponse getAllOpenOrders(List<String> addresses, String tokenid, BlockStoreInterface store)
			throws BlockStoreException {
		List<OrderRecord> allOrdersSorted = store.getAllOpenOrdersSorted(addresses, tokenid);

		HashSet<String> orderBlockHashs = new HashSet<>();
		for (OrderRecord orderRecord : allOrdersSorted) {
			orderBlockHashs.add(orderRecord.getBlockHashHex());
		}

		List<OrderCancel> orderCancels = store.getOrderCancelByOrderBlockHash(orderBlockHashs);
		HashMap<String, OrderCancel> orderCannelData = new HashMap<>();
		for (OrderCancel orderCancel : orderCancels) {
			orderCannelData.put(orderCancel.getOrderBlockHash().toString(), orderCancel);
		}

		for (OrderRecord orderRecord : allOrdersSorted) {
            orderRecord.setCancelPending(orderCannelData.containsKey(orderRecord.getBlockHashHex()));
		}

		return OrderdataResponse.createOrderRecordResponse(allOrdersSorted, getTokename(allOrdersSorted, store));
	}

	public Map<String, Token> getTokename(List<OrderRecord> allOrdersSorted, BlockStoreInterface store)
			throws BlockStoreException {
		Set<String> tokenids = new HashSet<>();
		for (OrderRecord d : allOrdersSorted) {
			tokenids.add(d.getOfferTokenid());
			tokenids.add(d.getTargetTokenid());
		}
		Map<String, Token> re = new HashMap<>();
		List<Token> tokens = store.getTokensList(tokenids);
		for (Token t : tokens) {
			re.put(t.getTokenid(), t);
		}
		return re;
	}

}
