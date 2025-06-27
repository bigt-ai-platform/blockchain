import { AbstractResponse } from './AbstractResponse';
import { Block } from '../core/Block';
import { Utils } from '../utils/Utils';

export class AskTransactionResponse extends AbstractResponse {
    private r1Hex: string | null = null;
    private r2Hex: string | null = null;

    public static create(result: Map<string, Block>): AskTransactionResponse {
        const res = new AskTransactionResponse();
        const b1 = result.get("r1");
        const b2 = result.get("r2");
        if (b1) {
            res.r1Hex = Utils.HEX.encode(b1.bitcoinSerialize());
        }
        if (b2) {
            res.r2Hex = Utils.HEX.encode(b2.bitcoinSerialize());
        }
        res.setErrorcode(0);
        return res;
    }

    public getR1Hex(): string | null {
        return this.r1Hex;
    }

    public setR1Hex(r1Hex: string | null): void {
        this.r1Hex = r1Hex;
    }

    public getR2Hex(): string | null {
        return this.r2Hex;
    }

    public setR2Hex(r2Hex: string | null): void {
        this.r2Hex = r2Hex;
    }

    // The Java method `create(Block block)` returns null, so we'll replicate that behavior.
    public static createFromBlock(block: Block): AbstractResponse | null {
        return null;
    }
}
