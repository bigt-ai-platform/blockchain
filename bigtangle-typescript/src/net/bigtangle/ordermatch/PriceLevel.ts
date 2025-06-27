import { Side } from '../core/Side';
import { Order } from './Order';
import { OrderBookListener } from './OrderBookListener';

export class PriceLevel {
    private side: Side;
    private price: number;
    private orders: Order[];

    constructor(side: Side, price: number) {
        this.side = side;
        this.price = price;
        this.orders = [];
    }

    public getSide(): Side {
        return this.side;
    }

    public getPrice(): number {
        return this.price;
    }

    public isEmpty(): boolean {
        return this.orders.length === 0;
    }

    public add(orderId: string, size: number): Order {
        const order = new Order(this, orderId, size);
        this.orders.push(order);
        return order;
    }

    public match(orderId: string, side: Side, quantity: number, listener: OrderBookListener): number {
        while (quantity > 0 && this.orders.length > 0) {
            const order = this.orders[0];
            const orderQuantity = order.getRemainingQuantity();

            if (orderQuantity > quantity) {
                order.reduce(quantity);
                listener.match(order.getId(), orderId, side, this.price, quantity, order.getRemainingQuantity());
                quantity = 0;
            } else {
                this.orders.shift(); // Remove the first element
                listener.match(order.getId(), orderId, side, this.price, orderQuantity, 0);
                quantity -= orderQuantity;
            }
        }
        return quantity;
    }

    public delete(order: Order): void {
        const index = this.orders.indexOf(order);
        if (index > -1) {
            this.orders.splice(index, 1);
        }
    }
}
