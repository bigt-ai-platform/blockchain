package net.bigtangle.server.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.ordermatch.TradePair;
import net.bigtangle.ordermatch.OrderBookEvents.Event;

public class OrderMatchingResult {
    Set<OrderRecord> spentOrders;
    Transaction outputTx;
    Collection<OrderRecord> remainingOrders; 
    Map<TradePair, List<Event>> tokenId2Events;

    public OrderMatchingResult() {

    }

    public OrderMatchingResult(Set<OrderRecord> spentOrders, Transaction outputTx,
            Collection<OrderRecord> remainingOrders, Map<TradePair, List<Event>> tokenId2Events) {
        this.spentOrders = spentOrders;
        this.outputTx = outputTx;
        this.remainingOrders = remainingOrders;
        this.tokenId2Events = tokenId2Events;

    }

    /*
     * This is unique for OrderMatchingResul
     */
    public Sha256Hash getOrderMatchingResultHash( ) {
        return  getOutputTx().getHash();
    }

 
 
    public Set<OrderRecord> getSpentOrders() {
        return spentOrders;
    }

    public void setSpentOrders(Set<OrderRecord> spentOrders) {
        this.spentOrders = spentOrders;
    }

 
    public Transaction getOutputTx() {
		return outputTx;
	}

	public void setOutputTx(Transaction outputTx) {
		this.outputTx = outputTx;
	}

	public Collection<OrderRecord> getRemainingOrders() {
        return remainingOrders;
    }

    public void setRemainingOrders(Collection<OrderRecord> remainingOrders) {
        this.remainingOrders = remainingOrders;
    }

    public Map<TradePair, List<Event>> getTokenId2Events() {
        return tokenId2Events;
    }

    public void setTokenId2Events(Map<TradePair, List<Event>> tokenId2Events) {
        this.tokenId2Events = tokenId2Events;
    }

 
     

}