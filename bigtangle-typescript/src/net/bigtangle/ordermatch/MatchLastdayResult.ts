export class MatchLastdayResult {
    private basetokenid: string;
    private price: number; // Assuming price is a number

    constructor(basetokenid: string, price: number) {
        this.basetokenid = basetokenid;
        this.price = price;
    }

    getBasetokenid(): string {
        return this.basetokenid;
    }

    setBasetokenid(basetokenid: string): void {
        this.basetokenid = basetokenid;
    }

    getPrice(): number {
        return this.price;
    }

    setPrice(price: number): void {
        this.price = price;
    }
}
