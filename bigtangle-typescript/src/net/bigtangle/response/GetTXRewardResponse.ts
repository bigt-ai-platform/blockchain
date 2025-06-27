import { AbstractResponse } from './AbstractResponse';
import { TXReward } from '../core/TXReward';

export class GetTXRewardResponse extends AbstractResponse {
    private txReward: TXReward | null = null;

    public static create(txReward: TXReward): GetTXRewardResponse {
        const res = new GetTXRewardResponse();
        res.txReward = txReward;
        return res;
    }

    public getTxReward(): TXReward | null {
        return this.txReward;
    }

    public setTxReward(txReward: TXReward | null): void {
        this.txReward = txReward;
    }
}
