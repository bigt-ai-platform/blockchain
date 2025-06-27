import { PriceLevel } from './PriceLevel';

export class Order {
    private level: PriceLevel;
    private id: string;
    private remainingQuantity: number;

    constructor(level: PriceLevel, id: string, size: number) {
        this.level = level;
        this.id = id;
        this.remainingQuantity = size;
    }

    public getLevel(): PriceLevel {
        return this.level;
    }

    public getId(): string {
        return this.id;
    }

    public getRemainingQuantity(): number {
        return this.remainingQuantity;
    }

    public reduce(quantity: number): void {
        this.remainingQuantity -= quantity;
    }

    public resize(size: number): void {
        this.remainingQuantity = size;
    }
}
