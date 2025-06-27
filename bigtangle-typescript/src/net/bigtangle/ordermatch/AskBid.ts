export class AskBid {
    private tradesystem: string | null = null;
    private eventTime: number = 0;
    private symbol: string | null = null;
    private askBestPrice: string | null = null; // Using string for BigDecimal
    private askBestAmount: string | null = null; // Using string for BigDecimal
    private bidBestPrice: string | null = null; // Using string for BigDecimal
    private bidBestAmount: string | null = null; // Using string for BigDecimal

    public getTradesystem(): string | null {
        return this.tradesystem;
    }

    public setTradesystem(tradesystem: string | null): void {
        this.tradesystem = tradesystem;
    }

    public getEventTime(): number {
        return this.eventTime;
    }

    public setEventTime(eventTime: number): void {
        this.eventTime = eventTime;
    }

    public getSymbol(): string | null {
        return this.symbol;
    }

    public setSymbol(symbol: string | null): void {
        this.symbol = symbol;
    }

    public getAskBestPrice(): string | null {
        return this.askBestPrice;
    }

    public setAskBestPrice(askBestPrice: string | null): void {
        this.askBestPrice = askBestPrice;
    }

    public getAskBestAmount(): string | null {
        return this.askBestAmount;
    }

    public setAskBestAmount(askBestAmount: string | null): void {
        this.askBestAmount = askBestAmount;
    }

    public getBidBestPrice(): string | null {
        return this.bidBestPrice;
    }

    public setBidBestPrice(bidBestPrice: string | null): void {
        this.bidBestPrice = bidBestPrice;
    }

    public getBidBestAmount(): string | null {
        return this.bidBestAmount;
    }

    public setBidBestAmount(bidBestAmount: string | null): void {
        this.bidBestAmount = bidBestAmount;
    }

    public toString(): string {
        return `AskBid [tradesystem=${this.tradesystem}, eventTime=${this.eventTime}, symbol=${this.symbol}` +
               `, askBestPrice=${this.askBestPrice}, askBestAmount=${this.askBestAmount}, bidBestPrice=${this.bidBestPrice}` +
               `, bidBestAmount=${this.bidBestAmount}]`;
    }
}