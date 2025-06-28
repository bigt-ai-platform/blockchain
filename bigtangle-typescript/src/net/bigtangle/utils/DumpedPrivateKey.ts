import { NetworkParameters } from '../core/NetworkParameters.js';
import { VersionedChecksummedBytes } from '../core/VersionedChecksummedBytes.js';
import { AddressFormatException } from '../exception/AddressFormatException.js';
import { WrongNetworkException } from '../exception/WrongNetworkException.js';
import { ECKey } from '../core/ECKey.js';
import { Base58 } from '../utils/Base58.js';
import bigInt from 'big-integer';
import { secp256k1 } from '@noble/curves/secp256k1';
import { BigInteger } from '../core/BigInteger.js';

export class DumpedPrivateKey extends VersionedChecksummedBytes {
  public compressed: boolean;
  public network: NetworkParameters | null;

  public static fromBase58(params: NetworkParameters | null, base58: string): DumpedPrivateKey {
    return new DumpedPrivateKey(params, base58);
  }

  constructor(params: NetworkParameters, keyBytes: Uint8Array, compressed: boolean);
  constructor(params: NetworkParameters | null, encoded: string);
  constructor(...args: any[]) {
    if (args.length === 2 && typeof args[1] === 'string') {
      // Constructor with params and encoded string
      const [params, encoded] = args as [NetworkParameters | null, string];
      super(encoded);
      this.network = params;
      if (params && this.version !== params.getDumpedPrivateKeyHeader()) {
        throw new WrongNetworkException(
          this.version ?? 0, 
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
      // Debug: print decoded private key bytes
      console.log('DumpedPrivateKey decoded bytes:', Array.from(this.bytes).map(b => b.toString(16).padStart(2, '0')).join(''));
    } else if (args.length === 3 && args[1] instanceof Uint8Array) {
      // Constructor with params, keyBytes, and compressed
      const [params, keyBytes, compressed] = args as [NetworkParameters, Uint8Array, boolean];
      const version = params.getDumpedPrivateKeyHeader();
      const bytes = DumpedPrivateKey.encode(keyBytes, compressed);
      super(version, bytes);
      this.compressed = compressed;
      this.network = params;
    } else {
      throw new Error('Invalid constructor arguments for DumpedPrivateKey');
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
    // Validate private key is in [1, N-1]
    const N = secp256k1.CURVE.n;
    // Convert bytes to hex string for bigInt and BigInteger
    const hex = Array.from(this.bytes).map(b => b.toString(16).padStart(2, '0')).join('');
    const keyInt = bigInt(hex, 16);
    if (keyInt.lesser(1) || keyInt.greaterOrEquals(bigInt(N))) {
      throw new Error('DumpedPrivateKey: private key out of range [1..N-1]');
    }
    // Use jsbn BigInteger for ECKey
    const priv = new BigInteger(hex, 16);
    return ECKey.fromPrivate(priv);
  }

  public clone(): DumpedPrivateKey {
    // Reconstruct using the same params, bytes, and compressed flag
    if (!this.network) {
      throw new Error('Cannot clone DumpedPrivateKey without network parameters');
    }
    return new DumpedPrivateKey(
      this.network,
      this.bytes.slice(0),
      this.compressed
    );
  }

  public toBase58(): string {
    // Use the base class's toBase58 logic, but ensure the compression flag is appended if needed
    let bytes = this.bytes;
    if (this.compressed) {
      const b = new Uint8Array(33);
      b.set(this.bytes, 0);
      b[32] = 1;
      bytes = b;
    }
    // Use the base class's static encode method if available, otherwise encode manually
    const versioned = new Uint8Array(1 + bytes.length);
    versioned[0] = this.version!;
    versioned.set(bytes, 1);
    return Base58.encode(versioned);
  }
}
