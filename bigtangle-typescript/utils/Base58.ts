import { Sha256Hash } from '../Sha256Hash';
import { AddressFormatException } from '../exception/AddressFormatException';

export class Base58 {
  public static readonly ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
  private static readonly ENCODED_ZERO = Base58.ALPHABET[0];
  private static readonly INDEXES: number[] = Base58.buildIndexes();

  private static buildIndexes(): number[] {
    const indexes = new Array(128).fill(-1);
    for (let i = 0; i < Base58.ALPHABET.length; i++) {
      indexes[Base58.ALPHABET.charCodeAt(i)] = i;
    }
    return indexes;
  }

  public static encode(input: Uint8Array): string {
    if (input.length === 0) {
      return '';
    }

    // Count leading zeros
    let zeros = 0;
    while (zeros < input.length && input[zeros] === 0) {
      zeros++;
    }

    // Convert base-256 digits to base-58 digits
    const inputCopy = new Uint8Array(input);
    const encoded: string[] = [];
    let outputStart = 0;
    
    for (let inputStart = zeros; inputStart < inputCopy.length; ) {
      const mod = this.divmod(inputCopy, inputStart, 256, 58);
      encoded.unshift(Base58.ALPHABET[mod]);
      if (inputCopy[inputStart] === 0) {
        inputStart++; // Skip leading zeros
      }
    }

    // Preserve leading zeros
    while (outputStart < encoded.length && encoded[outputStart] === Base58.ENCODED_ZERO) {
      outputStart++;
    }
    for (let i = 0; i < zeros; i++) {
      encoded.unshift(Base58.ENCODED_ZERO);
    }

    return encoded.join('');
  }

  public static decode(input: string): Uint8Array {
    if (input.length === 0) {
      return new Uint8Array(0);
    }

    // Convert base58 string to base58 byte sequence
    const input58 = new Uint8Array(input.length);
    for (let i = 0; i < input.length; i++) {
      const c = input.charAt(i);
      const charCode = c.charCodeAt(0);
      if (charCode > 127) {
        throw new AddressFormatException(`Illegal character ${c} at position ${i}`);
      }
      const digit = Base58.INDEXES[charCode];
      if (digit < 0) {
        throw new AddressFormatException(`Illegal character ${c} at position ${i}`);
      }
      input58[i] = digit;
    }

    // Count leading zeros
    let zeros = 0;
    while (zeros < input58.length && input58[zeros] === 0) {
      zeros++;
    }

    // Convert base-58 digits to base-256 digits
    const decoded = new Uint8Array(input.length);
    let outputStart = decoded.length;
    
    for (let inputStart = zeros; inputStart < input58.length; ) {
      decoded[--outputStart] = this.divmod(input58, inputStart, 58, 256);
      if (input58[inputStart] === 0) {
        inputStart++; // Skip leading zeros
      }
    }

    // Ignore extra leading zeros
    while (outputStart < decoded.length && decoded[outputStart] === 0) {
      outputStart++;
    }

    // Return decoded data with original leading zeros
    return decoded.slice(outputStart - zeros);
  }

  public static decodeChecked(input: string): Uint8Array {
    const decoded = this.decode(input);
    if (decoded.length < 4) {
      throw new AddressFormatException('Input too short');
    }

    const data = decoded.slice(0, decoded.length - 4);
    const checksum = decoded.slice(decoded.length - 4);
    
    const hash = Sha256Hash.hashTwice(data);
    const actualChecksum = hash.slice(0, 4);
    
    if (!Base58.arraysEqual(checksum, actualChecksum)) {
      throw new AddressFormatException('Checksum does not validate');
    }
    
    return data;
  }

  private static divmod(number: Uint8Array, start: number, base: number, divisor: number): number {
    let remainder = 0;
    for (let i = start; i < number.length; i++) {
      const digit = number[i] & 0xff;
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
