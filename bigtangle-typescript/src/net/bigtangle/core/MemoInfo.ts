import { Utils } from './Utils';

export class MemoInfo {
    private memo: Uint8Array;

    constructor(memo: string | Uint8Array) {
        if (typeof memo === 'string') {
            this.memo = Utils.UTF8.encode(memo);
        } else {
            this.memo = memo;
        }
    }

    getMemo(): Uint8Array {
        return this.memo;
    }

    getMemoStr(): string {
        return Utils.UTF8.decode(this.memo);
    }

    toByteArray(): Uint8Array {
        return this.memo;
    }
}
