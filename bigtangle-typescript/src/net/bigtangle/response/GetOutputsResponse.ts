import { AbstractResponse } from './AbstractResponse';
import { Token } from '../core/Token';
import { UTXO } from '../core/UTXO';

export class GetOutputsResponse extends AbstractResponse {
    private outputs: UTXO[] | null = null;
    private tokennames: Map<string, Token> | null = null;

    public static create(outputs: UTXO[], tokennames: Map<string, Token>): GetOutputsResponse {
        const res = new GetOutputsResponse();
        res.outputs = outputs;
        res.tokennames = tokennames;
        return res;
    }

    public getOutputs(): UTXO[] | null {
        return this.outputs;
    }

    public getTokennames(): Map<string, Token> | null {
        return this.tokennames;
    }

    public setTokennames(tokennames: Map<string, Token> | null): void {
        this.tokennames = tokennames;
    }

    public setOutputs(outputs: UTXO[] | null): void {
        this.outputs = outputs;
    }
}