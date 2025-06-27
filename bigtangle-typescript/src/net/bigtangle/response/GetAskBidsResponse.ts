import { AbstractResponse } from './AbstractResponse';

export class GetAskBidsResponse extends AbstractResponse {
    // Using Map<string, Map<string, string>> to represent Map<String, NavigableMap<BigDecimal, BigDecimal>>
    // BigDecimal values are represented as strings for precision.
    private askbids: Map<string, Map<string, string>> | null = null;

    public static create(askbids: Map<string, Map<string, string>>): GetAskBidsResponse {
        const res = new GetAskBidsResponse();
        res.askbids = askbids;
        return res;
    }

    public getAskbids(): Map<string, Map<string, string>> | null {
        return this.askbids;
    }

    public setAskbids(askbids: Map<string, Map<string, string>> | null): void {
        this.askbids = askbids;
    }
}
