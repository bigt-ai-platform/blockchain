import { NetworkParameters } from '../NetworkParameters';
import { VersionedChecksummedBytes } from '../VersionedChecksummedBytes';
import { AddressFormatException } from '../exception/AddressFormatException';
import { WrongNetworkException } from '../exception/WrongNetworkException';
import { ECKey } from '../ECKey';
import { Base58 } from './Base58';
import bigInt, { BigInteger } from 'big-integer';

export class DumpedPrivateKey extends VersionedChecksummedBytes {
  private compressed: boolean;

  public static fromBase58(params: NetworkParameters | null, base58: string): DumpedPrivateKey {
    return new DumpedPrivateKey(params, base58);
  }

  constructor(params: NetworkParameters, keyBytes: Uint8Array, compressed: boolean);
  constructor(params: NetworkParameters | null, encoded: string);
  constructor(...args: any[]) {
    if (args.length === 2) {
      // Constructor with params and encoded string
      const [params, encoded] = args as [NetworkParameters | null, string];
      super(encoded);
      
      // Access the dumpedPrivateKeyHeader through a public method
      if (params && this.version !== params.getDumpedPrivateKeyHeader()) {
        throw new WrongNetworkException(
          this.version, 
          [params.getDumpedPrivateKeyHeader()]
        );
      }

      if (this.bytes.length === 33 && this.bytes[32] === 1) {
        this.compressed = true;
        this.bytes = this.bytes.slice(0, 32);
      } else if (this.bytes.length === 32) {
        this.compressed = false;
      } else {
        throw new AddressFormatException('Wrong number of bytes for a private key, not 32 or 33');
      }
    } else {
      // Constructor with params, keyBytes, and compressed
      const [params, keyBytes, compressed] = args as [NetworkParameters, Uint8Array, boolean];
      super(params.getDumpedPrivateKeyHeader(), DumpedPrivateKey.encode(keyBytes, compressed));
      this.compressed = compressed;
    }
  }

  private static encode(keyBytes: Uint8Array, compressed: boolean): Uint8Array {
    if (keyBytes.length !== 32) {
      throw new Error('Private keys must be 32 bytes');
    }
    
    if (!compressed) {
      return keyBytes;
    } else {
      const bytes = new Uint8Array(33);
      bytes.set(keyBytes, 0);
      bytes[32] = 1;
      return bytes;
    }
  }

  public getKey(): ECKey {
    // Convert Uint8Array to BigInteger for ECKey
    const hexString = Array.from(this.bytes)
      .map(byte => byte.toString(16).padStart(2, '0'))
      .join('');
    const privateKey = bigInt(hexString, 16);
    return ECKey.fromPrivate(privateKey);
  }
}
