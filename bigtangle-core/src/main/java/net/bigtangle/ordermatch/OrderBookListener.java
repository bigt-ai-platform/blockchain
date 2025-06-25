/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.ordermatch;

import net.bigtangle.core.Side;

/**
 * The interface for outbound events from an order book.
 */
public interface OrderBookListener {

    /**
     * Match an incoming order to a resting order in the order book. The match
     * occurs at the price of the order in the order book.
     *
     * @param restingOrderId the order identifier of the resting order
     * @param incomingOrderId the order identifier of the incoming order
     * @param incomingSide the side of the incoming order
     * @param price the execution price
     * @param executedQuantity the executed quantity
     * @param remainingQuantity the remaining quantity of the resting order
     */
    void match(String restingOrderId, String incomingOrderId, Side incomingSide,
            long price, long executedQuantity, long remainingQuantity);

    /**
     * Add an order to the order book.
     *
     * @param orderId the order identifier
     * @param side the side
     * @param price the limit price
     * @param size the size
     */
    void add(String orderId, Side side, long price, long size);

    /**
     * Cancel a quantity of an order.
     *
     * @param orderId the order identifier
     * @param canceledQuantity the canceled quantity
     * @param remainingQuantity the remaining quantity
     */
    void cancel(String orderId, long canceledQuantity, long remainingQuantity);

}
