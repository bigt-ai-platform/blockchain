import { AbstractResponse } from './AbstractResponse';
import { Coin } from '../core/Coin';
import { Token } from '../core/Token';
import { UTXO } from '../core/UTXO';

export class GetBalancesResponse extends AbstractResponse {
    private outputs: UTXO[] | null = null;
    private balance: Coin[] | null = null;
    private tokennames: Map<string, Token> | null = null;

    public static create(coinbalance: Coin[], outputs: UTXO[], tokennames: Map<string, Token>): GetBalancesResponse {
        const res = new GetBalancesResponse();
        res.outputs = outputs;
        res.balance = coinbalance;
        res.tokennames = tokennames;
        return res;
    }

    public getOutputs(): UTXO[] | null {
        return this.outputs;
    }

    public getBalance(): Coin[] | null {
        return this.balance;
    }

    public getTokennames(): Map<string, Token> | null {
        return this.tokennames;
    }

    public setTokennames(tokennames: Map<string, Token> | null): void {
        this.tokennames = tokennames;
    }
}
