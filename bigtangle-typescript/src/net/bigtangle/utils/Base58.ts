import { Sha256Hash } from '../core/Sha256Hash';
import { Utils } from './Utils';

/**
 * Base58 encoding/decoding
 */
export class Base58 {
    private static readonly ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
    private static readonly BASE = BigInt(58);
    
    /**
     * Encodes a byte array to Base58 string
     */
    static encode(input: Uint8Array): string {
        if (input.length === 0) return '';
        
        let value = BigInt(0);
        for (let i = 0; i < input.length; i++) {
            value = value * BigInt(256) + BigInt(input[i]);
        }
        
        let result = '';
        while (value > 0) {
            const mod = Number(value % Base58.BASE);
            value = value / Base58.BASE;
            result = Base58.ALPHABET[mod] + result;
        }
        
        // Add leading '1's for each leading zero byte
        for (let i = 0; i < input.length; i++) {
            if (input[i] !== 0) break;
            result = '1' + result;
        }
        
        return result;
    }
    
    /**
     * Decodes a Base58 string to a byte array
     */
    static decode(input: string): Uint8Array {
        if (input.length === 0) return new Uint8Array(0);
        
        let value = BigInt(0);
        for (let i = 0; i < input.length; i++) {
            const char = input[i];
            const index = Base58.ALPHABET.indexOf(char);
            if (index === -1) throw new Error(`Invalid Base58 character: ${char}`);
            value = value * Base58.BASE + BigInt(index);
        }
        
        // Count leading zeros
        let leadingZeros = 0;
        for (let i = 0; i < input.length && input[i] === '1'; i++) {
            leadingZeros++;
        }
        
        // Convert to byte array
        const bytes: number[] = [];
        while (value > 0) {
            const mod = Number(value % BigInt(256));
            value = value / BigInt(256);
            bytes.unshift(mod);
        }
        
        // Add leading zeros
        const result = new Uint8Array(leadingZeros + bytes.length);
        for (let i = 0; i < leadingZeros; i++) {
            result[i] = 0;
        }
        for (let i = 0; i < bytes.length; i++) {
            result[leadingZeros + i] = bytes[i];
        }
        
        return result;
    }
    
    /**
     * Decodes a Base58 string and verifies the checksum
     */
    static decodeChecked(input: string): Uint8Array {
        const decoded = Base58.decode(input);
        if (decoded.length < 4) throw new Error('Input too short');
        
        const data = decoded.slice(0, decoded.length - 4);
        const checksum = decoded.slice(decoded.length - 4);

        // Ensure data is a Buffer if Sha256Hash.hashTwice expects Buffer, otherwise keep as Uint8Array
        // @ts-ignore
        const hash = Sha256Hash.hashTwice(Buffer.from(data));
        const expectedChecksum = hash.slice(0, 4);

        // Use Utils.arraysEqual instead of non-existent bytesEqual
        if (!Utils.arraysEqual(checksum, expectedChecksum)) {
            throw new Error('Checksum does not match');
        }
        
        return data;
    }
}
