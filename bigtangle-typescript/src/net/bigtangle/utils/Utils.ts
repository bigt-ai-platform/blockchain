import { sha256 } from '@noble/hashes/sha256';
import { ripemd160 } from '@noble/hashes/ripemd160';
import { BigInteger } from '../core/BigInteger';
import { VerificationException } from '../exception/VerificationException';
import { Base58 } from '../utils/Base58';
import { Buffer } from 'buffer';
import { ContractEventInfo } from '../core/ContractEventInfo';
import { Token } from '../core/Token';
import { TokenKeyValues } from '../core/TokenKeyValues';

import { DataOutputStream } from '../utils/DataOutputStream';
import { DataInputStream } from '../utils/DataInputStream';

// Minimal implementation for UnsafeByteArrayOutputStream
class UnsafeByteArrayOutputStream {
    private buffers: Uint8Array[] = [];
    write(data: Uint8Array) {
        this.buffers.push(data);
    }
    toByteArray(): Uint8Array {
        let totalLength = this.buffers.reduce((sum, b) => sum + b.length, 0);
        let result = new Uint8Array(totalLength);
        let offset = 0;
        for (const b of this.buffers) {
            result.set(b, offset);
            offset += b.length;
        }
        return result;
    }
}

/**
 * A collection of various utility methods that are helpful for working with the
 * Bitcoin protocol.
 */
export class Utils {

    public static readonly BITCOIN_SIGNED_MESSAGE_HEADER = "Bitcoin Signed Message:\n";
    public static readonly BITCOIN_SIGNED_MESSAGE_HEADER_BYTES = new TextEncoder().encode(Utils.BITCOIN_SIGNED_MESSAGE_HEADER);

    public static HEX = {
        encode: (data: Uint8Array): string => {
            return Array.from(data)
                .map(byte => byte.toString(16).padStart(2, '0'))
                .join('');
        },
        decode: (hex: string): Uint8Array => {
            if (hex.length % 2 !== 0) {
                throw new Error("Hex string must have an even number of characters");
            }
            const bytes = new Uint8Array(hex.length / 2);
            for (let i = 0; i < bytes.length; i++) {
                bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
            }
            return bytes;
        }
    };

    public static bigIntToBytes(b: BigInteger, numBytes: number): Uint8Array {
        if (b === null) {
            return new Uint8Array(0);
        }
        const biBytes = b.toByteArray();
        const bytes = new Uint8Array(numBytes);
        let start = 0;
        let length = biBytes.length;
        if (biBytes.length === numBytes + 1 && biBytes[0] === 0) {
            start = 1;
            length--;
        }
        if (length > numBytes) {
            length = numBytes;
        }
        for (let i = 0; i < length; i++) {
            bytes[numBytes - length + i] = biBytes[start + i];
        }
        return bytes;
    }

    public static uint32ToByteArrayBE(val: number, out: Uint8Array, offset: number): void {
        out[offset] = (val >>> 24) & 0xFF;
        out[offset + 1] = (val >>> 16) & 0xFF;
        out[offset + 2] = (val >>> 8) & 0xFF;
        out[offset + 3] = val & 0xFF;
    }

    public static uint32ToByteArrayLE(val: number, out: Uint8Array, offset: number): void {
        out[offset] = val & 0xFF;
        out[offset + 1] = (val >>> 8) & 0xFF;
        out[offset + 2] = (val >>> 16) & 0xFF;
        out[offset + 3] = (val >>> 24) & 0xFF;
    }

    public static uint64ToByteArrayLE(val: BigInteger, out: Uint8Array, offset: number): void {
        const bytes = Utils.bigIntToBytes(val, 8);
        for (let i = 0; i < 8; i++) {
            out[offset + i] = bytes[7 - i]; // Reverse for LE
        }
    }

    public static bytesToByteStream(b: Uint8Array, stream: any): void {
        stream.write(b);
    }

    public static uint32ToByteStreamLE(val: number, stream: any): void {
        stream.write(val & 0xFF);
        stream.write((val >>> 8) & 0xFF);
        stream.write((val >>> 16) & 0xFF);
        stream.write((val >>> 24) & 0xFF);
    }

    public static int64ToByteStreamLE(val: number, stream: any): void {
        stream.write(val & 0xFF);
        stream.write((val >>> 8) & 0xFF);
        stream.write((val >>> 16) & 0xFF);
        stream.write((val >>> 24) & 0xFF);
        stream.write((val / Math.pow(2, 32)) & 0xFF);
        stream.write((val / Math.pow(2, 40)) & 0xFF);
        stream.write((val / Math.pow(2, 48)) & 0xFF);
        stream.write((val / Math.pow(2, 56)) & 0xFF);
    }

    public static uint64ToByteStreamLEBigInt(val: BigInteger, stream: any): void {
        const bytes = Utils.bigIntToBytes(val, 8);
        for (let i = 0; i < 8; i++) {
            stream.write(bytes[7 - i]); // Reverse for LE
        }
    }

    public static isLessThanUnsigned(n1: number, n2: number): boolean {
        return (n1 < n2) !== (n1 < 0 !== n2 < 0);
    }

    public static isLessThanOrEqualToUnsigned(n1: number, n2: number): boolean {
        return (n1 <= n2) !== (n1 < 0 !== n2 < 0);
    }

    public static reverseBytes(bytes: Uint8Array): Uint8Array {
        const buf = new Uint8Array(bytes.length);
        for (let i = 0; i < bytes.length; i++) {
            buf[i] = bytes[bytes.length - 1 - i];
        }
        return buf;
    }

    public static reverseDwordBytes(bytes: Uint8Array, trimLength: number): Uint8Array {
        if (bytes.length % 4 !== 0) throw new Error("bytes.length must be divisible by 4");
        if (trimLength >= 0 && trimLength % 4 !== 0) throw new Error("trimLength must be divisible by 4 or negative");

        const rev = new Uint8Array(trimLength >= 0 && bytes.length > trimLength ? trimLength : bytes.length);

        for (let i = 0; i < rev.length; i += 4) {
            rev[i] = bytes[i + 3];
            rev[i + 1] = bytes[i + 2];
            rev[i + 2] = bytes[i + 1];
            rev[i + 3] = bytes[i];
        }
        return rev;
    }

    public static readUint32(bytes: Uint8Array, offset: number): number {
        return (bytes[offset] & 0xff) |
               ((bytes[offset + 1] & 0xff) << 8) |
               ((bytes[offset + 2] & 0xff) << 16) |
               ((bytes[offset + 3] & 0xff) << 24);
    }

    public static readInt64(bytes: Uint8Array, offset: number): number {
        const low = (bytes[offset] & 0xff) |
                    ((bytes[offset + 1] & 0xff) << 8) |
                    ((bytes[offset + 2] & 0xff) << 16) |
                    ((bytes[offset + 3] & 0xff) << 24);
        const high = (bytes[offset + 4] & 0xff) |
                     ((bytes[offset + 5] & 0xff) << 8) |
                     ((bytes[offset + 6] & 0xff) << 16) |
                     ((bytes[offset + 7] & 0xff) << 24);
        return low + high * Math.pow(2, 32);
    }

    public static readUint32BE(bytes: Uint8Array, offset: number): number {
        return ((bytes[offset] & 0xff) << 24) |
               ((bytes[offset + 1] & 0xff) << 16) |
               ((bytes[offset + 2] & 0xff) << 8) |
               (bytes[offset + 3] & 0xff);
    }

    public static readNBytesString(dis: DataInputStream): string | null {
        const hasValue = dis.readBoolean();
        if (hasValue) {
            const length = dis.readInt();
            const buf = dis.readBytes(length);
            return new TextDecoder('utf-8').decode(buf);
        } else {
            return null;
        }
    }

    public static readNBytes(dis: DataInputStream): Uint8Array | null {
        const hasValue = dis.readBoolean();
        if (hasValue) {
            const length = dis.readInt();
            const message = dis.readBytes(length);
            return message;
        } else {
            return null;
        }
    }

    public static writeNBytesString(dos: DataOutputStream, message: string | null): void {
        dos.writeBoolean(message !== null);
        if (message !== null) {
            const bytes = new TextEncoder().encode(message);
            dos.writeInt(bytes.length);
            dos.write(Buffer.from(bytes));
        }
    }

    public static writeLong(dos: DataOutputStream, message: number | null): void {
        dos.writeBoolean(message !== null);
        if (message !== null) {
            dos.writeLong(message);
        }
    }

    public static readLong(dis: DataInputStream): number | null {
        const hasValue = dis.readBoolean();
        if (hasValue) {
            return dis.readLong();
        } else {
            return null;
        }
    }

    public static writeNBytes(dos: DataOutputStream, message: Uint8Array | null): void {
        dos.writeBoolean(message !== null);
        if (message !== null) {
            dos.writeInt(message.length);
            dos.write(Buffer.from(message));
        }
    }

    public static readUint16BE(bytes: Uint8Array, offset: number): number {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    public static sha256hash160(input: Uint8Array): Uint8Array {
        const sha256Result = sha256(input);
        return ripemd160(sha256Result);
    }

    public static decodeMPI(mpi: Uint8Array, hasLength: boolean): BigInteger {
        let buf: Uint8Array;
        if (hasLength) {
            const length = Utils.readUint32BE(mpi, 0);
            buf = mpi.subarray(4, 4 + length);
        } else {
            buf = mpi;
        }
        if (buf.length === 0) {
            return BigInteger.ZERO;
        }
        const isNegative = (buf[0] & 0x80) === 0x80;
        if (isNegative) {
            buf[0] &= 0x7f;
        }
        const result = new BigInteger(Utils.HEX.encode(buf), 16);
        return isNegative ? result.negate() : result;
    }

    public static encodeMPI(value: BigInteger, includeLength: boolean): Uint8Array {
        if (value.equals(BigInteger.ZERO)) {
            if (!includeLength) {
                return new Uint8Array(0);
            } else {
                return new Uint8Array([0x00, 0x00, 0x00, 0x00]);
            }
        }
        const isNegative = value.signum() < 0;
        if (isNegative) {
            value = value.negate();
        }
        let array = Utils.bigIntToBytes(value, value.toByteArray().length); // Ensure no leading zero for positive numbers
        let length = array.length;
        if ((array[0] & 0x80) === 0x80) {
            length++;
        }
        if (includeLength) {
            const result = new Uint8Array(length + 4);
            if ((array[0] & 0x80) === 0x80) {
                result.set(array, 4 + 1); // Shift by 1 if leading bit is set
            } else {
                result.set(array, 4); 
            }
            Utils.uint32ToByteArrayBE(length, result, 0);
            if (isNegative) {
                result[4] |= 0x80;
            }
            return result;
        } else {
            let result: Uint8Array;
            if (length !== array.length) {
                result = new Uint8Array(length);
                result.set(array, 1);
            } else {
                result = array;
            }
            if (isNegative) {
                result[0] |= 0x80;
            }
            return result;
        }
    }

    public static decodeCompactBits(compact: number): BigInteger {
        const size = (compact >>> 24) & 0xFF;
        const bytes = new Uint8Array(4 + size);
        bytes[3] = size;
        if (size >= 1) bytes[4] = (compact >>> 16) & 0xFF;
        if (size >= 2) bytes[5] = (compact >>> 8) & 0xFF;
        if (size >= 3) bytes[6] = compact & 0xFF;
        return Utils.decodeMPI(bytes, true);
    }

    public static encodeCompactBits(value: BigInteger): number {
        let result: number;
        let size = value.toByteArray().length;
        if (size <= 3) {
            result = parseInt(value.toString(), 10) << (8 * (3 - size));
        } else {
            result = parseInt(value.shiftRight(8 * (size - 3)).toString(), 10);
        }
        // The 0x00800000 bit denotes the sign.
        // Thus, if it is already set, divide the mantissa by 256 and increase
        // the exponent.
        if ((result & 0x00800000) !== 0) {
            result >>= 8;
            size++;
        }
        result |= (size << 24);
        result |= value.signum() === -1 ? 0x00800000 : 0;
        return result;
    }

    public static mockTime: Date | null = null;

    public static rollMockClock(seconds: number): Date {
        return Utils.rollMockClockMillis(seconds * 1000);
    }

    public static rollMockClockMillis(millis: number): Date {
        if (Utils.mockTime === null) {
            throw new Error("You need to use setMockClock() first.");
        }
        Utils.mockTime = new Date(Utils.mockTime.getTime() + millis);
        return Utils.mockTime;
    }

    public static unsetMockClock(): void {
        Utils.mockTime = null;
    }

    public static setMockClock(): void {
        Utils.mockTime = new Date();
    }

    public static setMockClockWithSeconds(mockClockSeconds: number): void {
        Utils.mockTime = new Date(mockClockSeconds * 1000);
    }

    public static now(): Date {
        return Utils.mockTime !== null ? Utils.mockTime : new Date();
    }

    public static currentTimeMillis(): number {
        return Utils.mockTime !== null ? Utils.mockTime.getTime() : Date.now();
    }

    public static currentTimeSeconds(): number {
        return Math.floor(Utils.currentTimeMillis() / 1000);
    }

    public static dateTimeFormat(dateTime: Date | number): string {
        const date = typeof dateTime === 'number' ? new Date(dateTime) : dateTime;
        return date.toISOString().replace(/\.\d{3}Z$/, 'Z'); // Simple ISO 8601 format
    }

    public static join(items: Iterable<any>): string {
        return Array.from(items).join(" ");
    }

    public static copyOf(input: Buffer, length: number): Buffer {
        const out = Buffer.alloc(length);
        input.copy(out, 0, 0, Math.min(length, input.length));
        return out;
    }

    public static appendByte(bytes: Uint8Array, b: number): Uint8Array {
        const result = new Uint8Array(bytes.length + 1);
        result.set(bytes, 0);
        result[bytes.length] = b;
        return result;
    }

    public static toString(bytes: Uint8Array, charsetName: string): string {
        return new TextDecoder(charsetName).decode(bytes);
    }

    public static toBytes(str: string, charsetName: string): Uint8Array {
        return new TextEncoder().encode(str);
    }

    public static parseAsHexOrBase58(data: string): Uint8Array | null {
        try {
            return Utils.HEX.decode(data);
        } catch (e) {
            try {
                return Base58.decodeChecked(data);
            } catch (e1) {
                return null;
            }
        }
    }

    public static isWindows(): boolean {
        return false; // Node.js/browser environment, not Windows
    }
 

    public static checkBitLE(data: Uint8Array, index: number): boolean {
        return ((data[index >>> 3] || 0) & (1 << (7 & index))) !== 0;
    }

    public static setBitLE(data: Uint8Array, index: number): void {
        data[index >>> 3] |= (1 << (7 & index));
    }

    public static isBlank(cs: string | null | undefined): boolean {
        if (cs === null || cs === undefined || cs.length === 0) {
            return true;
        }
        for (let i = 0; i < cs.length; i++) {
            if (!/\s/.test(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static strLength(cs: string | null | undefined): number {
        return cs === null || cs === undefined ? 0 : cs.length;
    }

    public static addAll(array1: Uint8Array, array2: Uint8Array): Uint8Array {
        const joinedArray = new Uint8Array(array1.length + array2.length);
        joinedArray.set(array1, 0);
        joinedArray.set(array2, array1.length);
        return joinedArray;
    }

    public static clone(array: Uint8Array | null): Uint8Array | null {
        if (array === null) {
            return null;
        }
        return new Uint8Array(array);
    }

    public checkContractBase(contractEventInfo: ContractEventInfo, contract: Token): void {
        const amountStr = this.findContractValue(contract.getTokenKeyValues(), "amount");
        if (amountStr !== null) {
            const amount = new BigInteger(amountStr);
            if (contractEventInfo.getOfferValue() !== null && contractEventInfo.getOfferValue()!.mod(amount).compareTo(BigInteger.ZERO) !== 0) {
                throw new VerificationException(`only module base amount is allowed ${contractEventInfo.getOfferValue()} % ${amount}`);
            }
        }
        const tokenid = this.findContractValue(contract.getTokenKeyValues(), "token"); 
        if (tokenid !== null && contractEventInfo.getOfferTokenid() !== null && tokenid !== contractEventInfo.getOfferTokenid()) {
            throw new VerificationException("ContractEventInfo tokenidis not correct  ");
        }
    }

    public findContractValue(t: TokenKeyValues | null, key: string): string | null {
        if (t === null || t.getKeyvalues() === null) {
            return null;
        }
        for (const kv of t.getKeyvalues()!) {
            if (kv.getKey() === key) {
                return kv.getValue();
            }
        }
        return null;
    }

    static arraysEqual(a: Uint8Array, b: Uint8Array): boolean {
        if (a === b) return true;
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }
}