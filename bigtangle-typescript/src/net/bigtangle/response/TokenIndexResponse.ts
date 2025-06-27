import { AbstractResponse } from './AbstractResponse';
import { Sha256Hash } from '../core/Sha256Hash';

export class TokenIndexResponse extends AbstractResponse {
    private tokenindex: number = 0;
    private blockhash: Sha256Hash | null = null;

    public getTokenindex(): number {
        return this.tokenindex;
    }

    public getBlockhash(): Sha256Hash | null {
        return this.blockhash;
    }

    public static createTokenSerialIndexResponse(tokenindex: number, blockhash: Sha256Hash): TokenIndexResponse {
        const res = new TokenIndexResponse();
        res.tokenindex = tokenindex;
        res.blockhash = blockhash;
        return res;
    }
}