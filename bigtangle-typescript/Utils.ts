import bigInt, { BigInteger } from 'big-integer';
import { Sha256Hash } from './Sha256Hash';
import { DataInputStream } from './utils/DataInputStream';
import { DataOutputStream } from './utils/DataOutputStream';
import { ec } from 'elliptic';

const secp256k1 = new ec('secp256k1');

export class Utils {
    static HEX = {
        decode: (hex: string): Uint8Array => {
            if (hex.length % 2 !== 0) {
                throw new Error("Hex string must have an even number of characters.");
            }
            const bytes = new Uint8Array(hex.length / 2);
            for (let i = 0; i < hex.length; i += 2) {
                bytes[i / 2] = parseInt(hex.substring(i, i + 2), 16);
            }
            return bytes;
        },
        encode: (bytes: Uint8Array): string => {
            return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
        }
    };

    static sha256hash160(bytes: Uint8Array): Uint8Array {
        // Simplified: In a real implementation, this would perform SHA-256 then RIPEMD-160
        // For now, return a dummy hash.
        const dummyHash = new Uint8Array(20); // RIPEMD-160 produces 20 bytes
        for (let i = 0; i < dummyHash.length; i++) {
            dummyHash[i] = Math.floor(Math.random() * 256);
        }
        return dummyHash;
    }

    static bigIntegerToBytes(val: BigInteger, numBytes: number): Uint8Array {
        // Convert BigInteger to fixed-size byte array
        const bytes = new Uint8Array(numBytes);
        const valArray = val.toArray(256).value;
        const valBytes = new Uint8Array(valArray);
        const offset = numBytes - valBytes.length;
        if (offset < 0) {
            return valBytes.slice(-numBytes);
        }
        for (let i = 0; i < valBytes.length; i++) {
            bytes[offset + i] = valBytes[i];
        }
        return bytes;
    }

    static bytesToBigInteger(bytes: Uint8Array): BigInteger {
        let result = bigInt(0);
        for (let i = 0; i < bytes.length; i++) {
            result = result.shiftLeft(8).or(bigInt(bytes[i]));
        }
        return result;
    }

    static currentTimeSeconds(): number {
        return Math.floor(Date.now() / 1000);
    }

    static isAndroidRuntime(): boolean {
        return false; // Assuming not Android for this environment
    }

    static formatMessageForSigning(message: string): Uint8Array {
        // Bitcoin message signing format: prefix + VarInt(message length) + message
        const prefix = "Bitcoin Signed Message:\n";
        const prefixBytes = new TextEncoder().encode(prefix);
        const messageBytes = new TextEncoder().encode(message);
        
        // Create VarInt for message length
        let varIntBytes;
        const len = messageBytes.length;
        if (len < 0xFD) {
            varIntBytes = new Uint8Array([len]);
        } else if (len <= 0xFFFF) {
            varIntBytes = new Uint8Array(3);
            varIntBytes[0] = 0xFD;
            // Little-endian format
            varIntBytes[1] = len & 0xFF;
            varIntBytes[2] = (len >> 8) & 0xFF;
        } else if (len <= 0xFFFFFFFF) {
            varIntBytes = new Uint8Array(5);
            varIntBytes[0] = 0xFE;
            // Little-endian format
            for (let i = 0; i < 4; i++) {
                varIntBytes[1 + i] = (len >> (i * 8)) & 0xFF;
            }
        } else {
            varIntBytes = new Uint8Array(9);
            varIntBytes[0] = 0xFF;
            // Little-endian format
            for (let i = 0; i < 8; i++) {
                varIntBytes[1 + i] = (len >> (i * 8)) & 0xFF;
            }
        }
        
        // Combine prefix, VarInt, and message
        const result = new Uint8Array(prefixBytes.length + varIntBytes.length + messageBytes.length);
        result.set(prefixBytes, 0);
        result.set(varIntBytes, prefixBytes.length);
        result.set(messageBytes, prefixBytes.length + varIntBytes.length);
        
        return result;
    }

    static twiceOf(data: Uint8Array): Sha256Hash {
        // Simplified: Double SHA-256 hash
        // In a real implementation, you'd use a crypto library
        const hash1 = new Uint8Array(32); // Dummy hash
        const hash2 = new Uint8Array(32); // Dummy hash
        for (let i = 0; i < 32; i++) {
            hash1[i] = Math.floor(Math.random() * 256);
            hash2[i] = Math.floor(Math.random() * 256);
        }
        return new Sha256Hash(hash2); // Return a dummy Sha256Hash
    }

    static isBlank(str: string | null | undefined): boolean {
        return str === null || str === undefined || str.trim().length === 0;
    }

    static arraysEqual(a: Uint8Array, b: Uint8Array): boolean {
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }

    static doubleDigest(data: Uint8Array): Uint8Array {
        // This is a placeholder implementation
        // In a real implementation, this would perform SHA-256 twice
        const hash = new Uint8Array(32);
        for (let i = 0; i < 32; i++) {
            hash[i] = data.reduce((acc, byte) => acc ^ byte, i) & 0xFF;
        }
        return hash;
    }

    static unsetMockClock(): void {
        // No-op for now, as we don't have a mock clock
    }

    static bytesToBase64(bytes: Uint8Array): string {
        return Buffer.from(bytes).toString('base64');
    }

    static base64ToBytes(base64: string): Uint8Array {
        return new Uint8Array(Buffer.from(base64, 'base64'));
    }

    static UTF8 = {
        encode: (str: string): Uint8Array => {
            return new TextEncoder().encode(str);
        },
        decode: (bytes: Uint8Array): string => {
            return new TextDecoder().decode(bytes);
        }
    };

    static encodeCompactBits(nCompact: BigInteger): number {
        let nSize = nCompact.shiftRight(24).toJSNumber();
        let nBytes = new Uint8Array(4);
        if (nSize <= 3) {
            nBytes[0] = nCompact.and(bigInt(0xFF)).toJSNumber();
            nBytes[1] = nCompact.shiftRight(8).and(bigInt(0xFF)).toJSNumber();
            nBytes[2] = nCompact.shiftRight(16).and(bigInt(0xFF)).toJSNumber();
        } else {
            nBytes[0] = nCompact.shiftRight((nSize - 1) * 8).and(bigInt(0xFF)).toJSNumber();
            nBytes[1] = nCompact.shiftRight((nSize - 2) * 8).and(bigInt(0xFF)).toJSNumber();
            nBytes[2] = nCompact.shiftRight((nSize - 3) * 8).and(bigInt(0xFF)).toJSNumber();
        }
        return (nSize << 24) | (nBytes[2] << 16) | (nBytes[1] << 8) | nBytes[0];
    }

    static decodeCompactBits(nCompact: number): BigInteger {
        let nSize = nCompact >> 24;
        let nWord = nCompact & 0x007fffff;
        if (nSize <= 3) {
            return bigInt(String(nWord));
        } else {
            return bigInt(String(nWord)).shiftLeft(8 * (nSize - 3));
        }
    }

    static encodeMPI(value: BigInteger, includeLength: boolean): Uint8Array {
        if (value.equals(bigInt.zero)) {
            if (includeLength) return new Uint8Array([0x00, 0x00, 0x00, 0x00]);
            else return new Uint8Array();
        }

        // Get the bytes using toArray(256).value
        const valArray = value.toArray(256).value;
        let bytes = new Uint8Array(valArray);
        
        // The original logic for stripping sign byte might not be necessary with big-integer
        // But we'll keep it similar to the original implementation
        let stripSignByte = bytes.length > 1 && bytes[0] === 0 && (bytes[1] & 0x80) === 0;
        let fixedLength = bytes.length - (stripSignByte ? 1 : 0);

        let result = new Uint8Array(fixedLength + (includeLength ? 4 : 0));
        let offset = includeLength ? 4 : 0;

        if (stripSignByte) {
            result.set(bytes.slice(1), offset);
        } else {
            result.set(bytes, offset);
        }

        if (includeLength) {
            result[0] = fixedLength & 0xFF;
            result[1] = (fixedLength >> 8) & 0xFF;
            result[2] = (fixedLength >> 16) & 0xFF;
            result[3] = (fixedLength >> 24) & 0xFF;
        }
        return result;
    }

    static decodeMPI(mpi: Uint8Array, includeLength: boolean): BigInteger {
        let buf: Uint8Array;
        if (includeLength) {
            let len = mpi[0] | (mpi[1] << 8) | (mpi[2] << 16) | (mpi[3] << 24);
            buf = mpi.slice(4, 4 + len);
        } else {
            buf = mpi;
        }
        return bigInt(Utils.HEX.encode(buf), 16);
    }

    static hashTwice(input: Uint8Array, offset: number = 0, length: number = input.length, input2?: Uint8Array, offset2: number = 0, length2: number = 0): Uint8Array {
        // This is a placeholder. In a real implementation, you'd use a crypto library
        // to perform SHA256 twice.
        // For now, return a dummy 32-byte array.
        const dummyHash = new Uint8Array(32);
        for (let i = 0; i < 32; i++) {
            dummyHash[i] = Math.floor(Math.random() * 256);
        }
        return dummyHash;
    }

    static reverseBytes(bytes: Uint8Array): Uint8Array {
        const reversed = new Uint8Array(bytes.length);
        for (let i = 0; i < bytes.length; i++) {
            reversed[i] = bytes[bytes.length - 1 - i];
        }
        return reversed;
    }

    static hashCode(bytes: Uint8Array): number {
        let hash = 0;
        for (let i = 0; i < bytes.length; i++) {
            const byte = bytes[i];
            hash = (hash << 5) - hash + byte;
            hash |= 0; // Convert to 32bit integer
        }
        return hash;
    }

    static uint32ToByteStreamLE(val: number, stream: { write: (data: Uint8Array) => void }): void {
        stream.write(new Uint8Array([
            val & 0xFF,
            (val >> 8) & 0xFF,
            (val >> 16) & 0xFF,
            (val >> 24) & 0xFF
        ]));
    }

    static uint64ToByteStreamLE(val: BigInteger, stream: { write: (data: Uint8Array) => void }): void {
        const bytes = new Uint8Array(8);
        for (let i = 0; i < 8; i++) {
            bytes[i] = val.shiftRight(i * 8).and(bigInt(0xFF)).toJSNumber();
        }
        stream.write(bytes);
    }

    static readNBytesString(dis: DataInputStream): string | null {
        if (dis.readBoolean()) {
            const len = dis.readInt();
            const buf = new Uint8Array(len);
            dis.readFully(buf);
            return new TextDecoder('utf-8').decode(buf);
        } else {
            return null;
        }
    }

    static writeNBytesString(dos: DataOutputStream, message: string | null): void {
        dos.writeBoolean(message != null);
        if (message != null) {
            const buf = new TextEncoder().encode(message);
            dos.writeInt(buf.length);
            dos.write(buf);
        }
    }

    static readLong(dis: DataInputStream): number | null {
        if (dis.readBoolean()) {
            return dis.readByte(); // Assuming long is a single byte for simplicity
        } else {
            return null;
        }
    }

    static writeLong(dos: DataOutputStream, message: number | null): void {
        dos.writeBoolean(message != null);
        if (message != null) {
            dos.writeByte(message);
        }
    }

    static readNBytes(dis: DataInputStream): Uint8Array | null {
        if (dis.readBoolean()) {
            const len = dis.readInt();
            const buf = new Uint8Array(len);
            dis.readFully(buf);
            return buf;
        } else {
            return null;
        }
    }

    static writeNBytes(dos: DataOutputStream, message: Uint8Array | null): void {
        dos.writeBoolean(message != null);
        if (message != null) {
            dos.writeInt(message.length);
            dos.write(message);
        }
    }

    static copyOf(inArray: Uint8Array, length: number): Uint8Array {
        const out = new Uint8Array(length);
        for (let i = 0; i < Math.min(length, inArray.length); i++) {
            out[i] = inArray[i];
        }
        return out;
    }

    static bytesToHex(bytes: Uint8Array): string {
        return Array.from(bytes)
            .map(b => b.toString(16).padStart(2, '0'))
            .join('');
    }
}
