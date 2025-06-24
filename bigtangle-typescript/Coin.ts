import { BigInteger } from 'jsbn';
import { NetworkParameters } from './NetworkParameters';
import { Utils } from './Utils';

export class Coin {
    private value: BigInteger;
    private tokenid: Uint8Array;

    static ZERO: Coin;
    static COIN: Coin;
    static SATOSHI: Coin;
    static NEGATIVE_SATOSHI: Coin;
    static FEE_DEFAULT: Coin;

    constructor(satoshis: BigInteger | number, tokenid: Uint8Array | string) {
        if (typeof satoshis === 'number') {
            this.value = new BigInteger(String(satoshis));
        } else {
            this.value = satoshis;
        }

        if (typeof tokenid === 'string') {
            this.tokenid = Utils.HEX.decode(tokenid);
        } else {
            this.tokenid = tokenid;
        }
    }

    static valueOf(satoshis: number, tokenid?: Uint8Array | string): Coin {
        if (tokenid === undefined) {
            return new Coin(satoshis, NetworkParameters.BIGTANGLE_TOKENID);
        } else if (typeof tokenid === 'string') {
            return new Coin(satoshis, Utils.HEX.decode(tokenid));
        } else {
            return new Coin(satoshis, tokenid);
        }
    }

    getValue(): BigInteger {
        return this.value;
    }

    setValue(value: BigInteger): void {
        this.value = value;
    }

    getTokenHex(): string {
        if (this.tokenid === null) {
            return "";
        }
        return Utils.HEX.encode(this.tokenid);
    }

    add(value: Coin): Coin {
        if (!Utils.arraysEqual(this.tokenid, value.tokenid)) {
            throw new Error("Token IDs do not match for addition.");
        }
        return new Coin(this.value.add(value.value), value.tokenid);
    }

    plus(value: Coin): Coin {
        return this.add(value);
    }

    subtract(value: Coin): Coin {
        if (!Utils.arraysEqual(this.tokenid, value.tokenid)) {
            throw new Error("Token IDs do not match for subtraction.");
        }
        return new Coin(this.value.subtract(value.value), value.tokenid);
    }

    minus(value: Coin): Coin {
        return this.subtract(value);
    }

    multiply(factor: number): Coin {
        return new Coin(this.value.multiply(new BigInteger(String(factor))), this.tokenid);
    }

    times(factor: number): Coin {
        return this.multiply(factor);
    }

    divide(divisor: Coin | number): BigInteger | Coin {
        if (divisor instanceof Coin) {
            return this.value.divide(divisor.value);
        } else {
            return new Coin(this.value.divide(new BigInteger(String(divisor))), this.tokenid);
        }
    }

    isPositive(): boolean {
        return this.signum() === 1;
    }

    isNegative(): boolean {
        return this.signum() === -1;
    }

    isZero(): boolean {
        return this.signum() === 0;
    }

    isBIG(): boolean {
        return Utils.arraysEqual(this.tokenid, NetworkParameters.BIGTANGLE_TOKENID);
    }

    isGreaterThan(other: Coin): boolean {
        return this.compareTo(other) > 0;
    }

    isLessThan(other: Coin): boolean {
        return this.compareTo(other) < 0;
    }

    signum(): number {
        return this.value.signum();
    }

    negate(): Coin {
        return new Coin(this.value.negate(), this.tokenid);
    }

    toString(): string {
        return `[${this.value}:${this.getTokenHex()}]`;
    }

    equals(obj: any): boolean {
        if (this === obj) return true;
        if (obj === null || !(obj instanceof Coin)) return false;
        const other = obj as Coin;
        if (!Utils.arraysEqual(this.tokenid, other.tokenid)) return false;
        return this.value.equals(other.value);
    }

    compareTo(other: Coin): number {
        return this.value.compareTo(other.value);
    }

    getTokenid(): Uint8Array {
        return this.tokenid;
    }
}

// Initialize static properties after class definition
export function initCoin() {
    Coin.ZERO = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
    Coin.COIN = Coin.valueOf(Number(new BigInteger("10").pow(NetworkParameters.BIGTANGLE_DECIMAL).toString()), NetworkParameters.BIGTANGLE_TOKENID);
    Coin.SATOSHI = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);
    Coin.NEGATIVE_SATOSHI = Coin.valueOf(-1, NetworkParameters.BIGTANGLE_TOKENID);
    Coin.FEE_DEFAULT = Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID);
}
