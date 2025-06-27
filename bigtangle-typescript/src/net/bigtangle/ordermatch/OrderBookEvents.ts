import { Side } from '../core/Side';

export interface OrderBookListener {
    match(restingOrderId: string, incomingOrderId: string, incomingSide: Side, price: number, executedQuantity: number, remainingQuantity: number): void;
    add(orderId: string, side: Side, price: number, size: number): void;
    cancel(orderId: string, canceledQuantity: number, remainingQuantity: number): void;
}

export class OrderBookEvents implements OrderBookListener {
    private events: OrderBookEvents.Event[];

    constructor() {
        this.events = [];
    }

    public collect(): OrderBookEvents.Event[] {
        return this.events;
    }

    public match(restingOrderId: string, incomingOrderId: string, incomingSide: Side, price: number, executedQuantity: number, remainingQuantity: number): void {
        this.events.push(
            new OrderBookEvents.Match(restingOrderId, incomingOrderId, incomingSide, price, executedQuantity, remainingQuantity)
        );
    }

    public add(orderId: string, side: Side, price: number, size: number): void {
        this.events.push(new OrderBookEvents.Add(orderId, side, price, size));
    }

    public cancel(orderId: string, canceledQuantity: number, remainingQuantity: number): void {
        this.events.push(new OrderBookEvents.Cancel(orderId, canceledQuantity, remainingQuantity));
    }
}

export namespace OrderBookEvents {
    export interface Event {}

    export class Match implements Event {
        public readonly restingOrderId: string;
        public readonly incomingOrderId: string;
        public readonly incomingSide: Side;
        public readonly price: number;
        public readonly executedQuantity: number;
        public readonly remainingQuantity: number;

        constructor(restingOrderId: string, incomingOrderId: string, incomingSide: Side, price: number, executedQuantity: number, remainingQuantity: number) {
            this.restingOrderId = restingOrderId;
            this.incomingOrderId = incomingOrderId;
            this.incomingSide = incomingSide;
            this.price = price;
            this.executedQuantity = executedQuantity;
            this.remainingQuantity = remainingQuantity;
        }
        
        public toString(): string {
            return `Match [restingOrderId=${this.restingOrderId}, incomingOrderId=${this.incomingOrderId}` +
                   `, incomingSide=${this.incomingSide}, price=${this.price}, executedQuantity=${this.executedQuantity}` +
                   `, remainingQuantity=${this.remainingQuantity}]`;
        }
    }

    export class Add implements Event {
        public readonly orderId: string;
        public readonly side: Side;
        public readonly price: number;
        public readonly size: number;

        constructor(orderId: string, side: Side, price: number, size: number) {
            this.orderId = orderId;
            this.side = side;
            this.price = price;
            this.size = size;
        }
        
        public toString(): string {
            return `Add [orderId=${this.orderId}, side=${this.side}, price=${this.price}, size=${this.size}]`;
        }
    }

    export class Cancel implements Event {
        public readonly orderId: string;
        public readonly canceledQuantity: number;
        public readonly remainingQuantity: number;

        constructor(orderId: string, canceledQuantity: number, remainingQuantity: number) {
            this.orderId = orderId;
            this.canceledQuantity = canceledQuantity;
            this.remainingQuantity = remainingQuantity;
        }
        
        public toString(): string {
            return `Cancel [orderId=${this.orderId}, canceledQuantity=${this.canceledQuantity}, remainingQuantity=${this.remainingQuantity}]`;
        }
    }
}