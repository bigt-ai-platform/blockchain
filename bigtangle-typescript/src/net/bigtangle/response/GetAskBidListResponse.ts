import { AbstractResponse } from './AbstractResponse';
import { AskBid } from '../ordermatch/AskBid';

export class GetAskBidListResponse extends AbstractResponse {
    private askbidlist: AskBid[] | null = null;

    public static create(askbidlist: AskBid[]): GetAskBidListResponse {
        const res = new GetAskBidListResponse();
        res.askbidlist = askbidlist;
        return res;
    }

    public getAskbidlist(): AskBid[] | null {
        return this.askbidlist;
    }

    public setAskbidlist(askbidlist: AskBid[] | null): void {
        this.askbidlist = askbidlist;
    }
}
