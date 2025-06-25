import { Buffer } from 'buffer';

export class Script {
    private data: Buffer;

    constructor(data: Buffer) {
        this.data = data;
    }

    public isPayToScriptHash(): boolean {
        // Simplified implementation
        return this.data.length === 23 && 
               this.data[0] === 0xa9 && 
               this.data[1] === 0x14 && 
               this.data[22] === 0x87;
    }

    public getPubKeyHash(): Buffer {
        // Extract public key hash from script
        if (this.isPayToScriptHash()) {
            return this.data.subarray(2, 22);
        }
        throw new Error("Not a P2SH script");
    }
}
