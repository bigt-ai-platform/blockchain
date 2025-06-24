import { MatchLastdayResult } from '../ordermatch/MatchLastdayResult';
import { Token } from '../Token';

export class OrderTickerResponse {
    private tickers: MatchLastdayResult[];
    private tokennames: Map<string, Token>; // Map<String, Token> in Java

    constructor(tickers: MatchLastdayResult[], tokennames: Map<string, Token>) {
        this.tickers = tickers;
        this.tokennames = tokennames;
    }

    getTickers(): MatchLastdayResult[] {
        return this.tickers;
    }

    setTickers(tickers: MatchLastdayResult[]): void {
        this.tickers = tickers;
    }

    getTokennames(): Map<string, Token> {
        return this.tokennames;
    }

    setTokennames(tokennames: Map<string, Token>): void {
        this.tokennames = tokennames;
    }
}
