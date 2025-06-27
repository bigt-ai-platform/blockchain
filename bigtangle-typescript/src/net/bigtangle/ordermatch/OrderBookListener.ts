import { Side } from '../core/Side';

/**
 * The interface for outbound events from an order book.
 */
export interface OrderBookListener {

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
    match(restingOrderId: string, incomingOrderId: string, incomingSide: Side,
            price: number, executedQuantity: number, remainingQuantity: number): void;

    /**
     * Add an order to the order book.
     *
     * @param orderId the order identifier
     * @param side the side
     * @param price the limit price
     * @param size the size
     */
    add(orderId: string, side: Side, price: number, size: number): void;

    /**
     * Cancel a quantity of an order.
     *
     * @param orderId the order identifier
     * @param canceledQuantity the canceled quantity
     * @param remainingQuantity the remaining quantity
     */
    cancel(orderId: string, canceledQuantity: number, remainingQuantity: number): void;

}
