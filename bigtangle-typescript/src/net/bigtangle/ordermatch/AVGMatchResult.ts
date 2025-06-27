import { MatchResult } from './MatchResult';

export class AVGMatchResult extends MatchResult {
    private avgprice: string | null = null; // Using string for BigDecimal
    private matchday: string | null = null;
    private hignprice: number = 0;
    private lowprice: number = 0;

    public getAvgprice(): string | null {
        return this.avgprice;
    }

    public setAvgprice(avgprice: string | null): void {
        this.avgprice = avgprice;
    }

    public getMatchday(): string | null {
        return this.matchday;
    }

    public setMatchday(matchday: string | null): void {
        this.matchday = matchday;
    }

    public getHignprice(): number {
        return this.hignprice;
    }

    public setHignprice(hignprice: number): void {
        this.hignprice = hignprice;
    }

    public getLowprice(): number {
        return this.lowprice;
    }

    public setLowprice(lowprice: number): void {
        this.lowprice = lowprice;
    }
}
