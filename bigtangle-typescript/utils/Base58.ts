import { BigInteger } from 'jsbn';
import { Sha256Hash } from '../Sha256Hash';
import { AddressFormatException } from '../exception/AddressFormatException';
import { Utils } from '../Utils'; // Import Utils

/**
 * Base58 is a way to encode Bitcoin addresses (or arbitrary data) as alphanumeric strings.
 * <p>
 * Note that this is not the same base58 as used by Flickr, which you may find referenced around the Internet.
 * <p>
 * You may want to consider working with {@link VersionedChecksummedBytes} instead, which
 * adds support for testing the prefix and suffix bytes commonly found in addresses.
 * <p>
 * Satoshi explains: why base-58 instead of standard base-64 encoding?
 * <ul>
 * <li>Don't want 0OIl characters that look the same in some fonts and
 *     could be used to create visually identical looking account numbers.</li>
 * <li>A string with non-alphanumeric characters is not as easily accepted as an account number.</li>
 * <li>E-mail usually won't line-break if there's no punctuation to break at.</li>
 * <li>Doubleclicking selects the whole number as one word if it's all alphanumeric.</li>
 * </ul>
 * <p>
 * However, note that the encoding/decoding runs in O(n&sup2;) time, so it is not useful for large data.
 * <p>
 * The basic idea of the encoding is to treat the data bytes as a large number represented using
 * base-256 digits, convert the number to be represented using base-58 digits, preserve the exact
 * number of leading zeros (which are otherwise lost during the mathematical operations on the
 * numbers), and finally represent the resulting base-58 digits as alphanumeric ASCII characters.
 */
export class Base58 {
    static ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".split('');
    private static ENCODED_ZERO = Base58.ALPHABET[0];
    private static INDEXES = new Array(128).fill(-1);

    static { // Static initializer block
        for (let i = 0; i < Base58.ALPHABET.length; i++) {
            Base58.INDEXES[Base58.ALPHABET[i].charCodeAt(0)] = i;
        }
    }

    /**
     * Encodes the given bytes as a base58 string (no checksum is appended).
     *
     * @param input the bytes to encode
     * @return the base58-encoded string
     */
    static encode(input: Uint8Array): string {
        if (input.length === 0) {
            return "";
        }

        // Count leading zeros.
        let zeros = 0;
        while (zeros < input.length && input[zeros] === 0) {
            ++zeros;
        }

        // Convert base-256 digits to base-58 digits (plus conversion to ASCII characters)
        const inputCopy = new Uint8Array(input); // since we modify it in-place
        const encoded = new Array<string>(input.length * 2); // upper bound
        let outputStart = encoded.length;

        for (let inputStart = zeros; inputStart < inputCopy.length;) {
            encoded[--outputStart] = Base58.ALPHABET[Base58.divmod(inputCopy, inputStart, 256, 58)];
            if (inputCopy[inputStart] === 0) {
                ++inputStart; // optimization - skip leading zeros
            }
        }

        // Preserve exactly as many leading encoded zeros in output as there were leading zeros in input.
        while (outputStart < encoded.length && encoded[outputStart] === Base58.ENCODED_ZERO) {
            ++outputStart;
        }
        while (--zeros >= 0) {
            encoded[--outputStart] = Base58.ENCODED_ZERO;
        }

        // Return encoded string (including encoded leading zeros).
        return encoded.slice(outputStart, encoded.length).join('');
    }

    /**
     * Decodes the given base58 string into the original data bytes.
     *
     * @param input the base58-encoded string to decode
     * @return the decoded data bytes
     * @throws AddressFormatException if the given string is not a valid base58 string
     */
    static decode(input: string): Uint8Array {
        if (input.length === 0) {
            return new Uint8Array(0);
        }

        // Convert the base58-encoded ASCII chars to a base58 byte sequence (base58 digits).
        const input58 = new Uint8Array(input.length);
        for (let i = 0; i < input.length; ++i) {
            const c = input.charCodeAt(i);
            const digit = c < 128 ? Base58.INDEXES[c] : -1;
            if (digit < 0) {
                throw new AddressFormatException(`Illegal character ${input.charAt(i)} at position ${i}`);
            }
            input58[i] = digit;
        }

        // Count leading zeros.
        let zeros = 0;
        while (zeros < input58.length && input58[zeros] === 0) {
            ++zeros;
        }

        // Convert base-58 digits to base-256 digits.
        const decoded = new Uint8Array(input.length);
        let outputStart = decoded.length;
        for (let inputStart = zeros; inputStart < input58.length;) {
            decoded[--outputStart] = Base58.divmod(input58, inputStart, 58, 256);
            if (input58[inputStart] === 0) {
                ++inputStart; // optimization - skip leading zeros
            }
        }

        // Ignore extra leading zeroes that were added during the calculation.
        while (outputStart < decoded.length && decoded[outputStart] === 0) {
            ++outputStart;
        }

        // Return decoded data (including original number of leading zeros).
        return decoded.slice(outputStart - zeros, decoded.length);
    }

    static decodeToBigInteger(input: string): BigInteger {
        // Convert Uint8Array to hex string, then to BigInteger
        return new BigInteger(Utils.HEX.encode(Base58.decode(input)), 16);
    }

    /**
     * Decodes the given base58 string into the original data bytes, using the checksum in the
     * last 4 bytes of the decoded data to verify that the rest are correct. The checksum is
     * removed from the returned data.
     *
     * @param input the base58-encoded string to decode (which should include the checksum)
     * @throws AddressFormatException if the input is not base 58 or the checksum does not validate.
     */
    static decodeChecked(input: string): Uint8Array {
        const decoded = Base58.decode(input);
        if (decoded.length < 4) {
            throw new AddressFormatException("Input too short");
        }
        const data = decoded.slice(0, decoded.length - 4);
        const checksum = decoded.slice(decoded.length - 4, decoded.length);
        const actualChecksum = Sha256Hash.hashTwice(data).getBytes().slice(0, 4);
        if (!Base58.arraysEqual(checksum, actualChecksum)) {
            throw new AddressFormatException("Checksum does not validate");
        }
        return data;
    }

    /**
     * Divides a number, represented as an array of bytes each containing a single digit
     * in the specified base, by the given divisor. The given number is modified in-place
     * to contain the quotient, and the return value is the remainder.
     *
     * @param number the number to divide
     * @param firstDigit the index within the array of the first non-zero digit
     *        (this is used for optimization by skipping the leading zeros)
     * @param base the base in which the number's digits are represented (up to 256)
     * @param divisor the number to divide by (up to 256)
     * @return the remainder of the division operation
     */
    private static divmod(number: Uint8Array, firstDigit: number, base: number, divisor: number): number {
        // this is just long division which accounts for the base of the input digits
        let remainder = 0;
        for (let i = firstDigit; i < number.length; i++) {
            const digit = number[i] & 0xFF;
            const temp = remainder * base + digit;
            number[i] = Math.floor(temp / divisor);
            remainder = temp % divisor;
        }
        return remainder;
    }

    private static arraysEqual(a: Uint8Array, b: Uint8Array): boolean {
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }
}
