import { AbstractResponse } from './AbstractResponse';
import { MatchLastdayResult } from '../ordermatch/MatchLastdayResult';
import { Token } from '../core/Token';

export class OrderTickerResponse extends AbstractResponse {
    private tickers: MatchLastdayResult[] | null = null;
    private tokennames: Map<string, Token> | null = null;

    public static createOrderRecordResponse(tickers: MatchLastdayResult[], tokennames: Map<string, Token>): OrderTickerResponse {
        const res = new OrderTickerResponse();
        res.tickers = tickers;
        res.tokennames = tokennames;
        return res;
    }

    public getTickers(): MatchLastdayResult[] | null {
        return this.tickers;
    }

    public setTickers(tickers: MatchLastdayResult[] | null): void {
        this.tickers = tickers;
    }

    public getTokennames(): Map<string, Token> | null {
        return this.tokennames;
    }

    public setTokennames(tokennames: Map<string, Token> | null): void {
        this.tokennames = tokennames;
    }
}