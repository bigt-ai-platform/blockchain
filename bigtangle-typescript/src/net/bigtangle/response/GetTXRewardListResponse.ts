import { AbstractResponse } from './AbstractResponse';
import { TXReward } from '../core/TXReward';

export class GetTXRewardListResponse extends AbstractResponse {
    private txReward: TXReward[] | null = null;

    public static create(txReward: TXReward[]): GetTXRewardListResponse {
        const res = new GetTXRewardListResponse();
        res.txReward = txReward;
        return res;
    }

    public getTxReward(): TXReward[] | null {
        return this.txReward;
    }

    public setTxReward(txReward: TXReward[] | null): void {
        this.txReward = txReward;
    }
}
