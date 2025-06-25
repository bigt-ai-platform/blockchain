import { VersionedChecksummedBytes } from './VersionedChecksummedBytes';
import { NetworkParameters } from './NetworkParameters';
import { AddressFormatException } from './exception/AddressFormatException';
import { WrongNetworkException } from './exception/WrongNetworkException';
import { Script } from './Script';
import { Networks } from './Networks';

/**
 * A Bitcoin address is derived from an elliptic curve public key plus a set of network parameters.
 */
export class Address extends VersionedChecksummedBytes {
  /** An address is a RIPEMD160 hash of a public key, therefore is always 160 bits or 20 bytes. */
  static LENGTH = 20;

  private params: NetworkParameters;

  /**
   * Construct an address from parameters, the address version, and the hash160 form.
   */
  constructor(params: NetworkParameters, version: number, hash160: Buffer) {
    super(version, hash160);
    if (!hash160 || hash160.length !== Address.LENGTH) {
      throw new Error("Addresses are 160-bit hashes, so you must provide 20 bytes");
    }
    if (!Address.isAcceptableVersion(params, version)) {
      throw new WrongNetworkException(version, params.getAcceptableAddressCodes());
    }
    this.params = params;
  }

  /**
   * Construct an address from its Base58 representation.
   * @param params The expected NetworkParameters or null if you don't want validation.
   * @param base58 The textual form of the address, such as "17kzeh4N8g49GFvdDzSf8PjaPfyoD1MndL".
   */
  static fromBase58(params: NetworkParameters | null, base58: string): Address {
    // Create a temporary instance to extract version and bytes
    const temp = new VersionedChecksummedBytes(base58);
    const version = temp.getVersion();
    const bytes = temp.getBytes();
    const hash160 = Buffer.from(bytes);
    
    if (params) {
      return new Address(params, version, hash160);
    }
    
    let paramsFound: NetworkParameters | null = null;
    for (const p of Networks.get()) {
      if (Address.isAcceptableVersion(p, version)) {
        paramsFound = p;
        break;
      }
    }
    if (!paramsFound) {
      throw new AddressFormatException("No network found for " + base58);
    }
    return new Address(paramsFound, version, hash160);
  }

  /** Returns an Address that represents the given P2SH script hash. */
  static fromP2SHHash(params: NetworkParameters, hash160: Buffer): Address {
    return new Address(params, params.getP2SHHeader(), hash160);
  }

  /** Returns an Address that represents the script hash extracted from the given scriptPubKey */
  static fromP2SHScript(params: NetworkParameters, scriptPubKey: Script): Address {
    if (!scriptPubKey.isPayToScriptHash()) {
      throw new Error("Not a P2SH script");
    }
    const pubKeyHash = scriptPubKey.getPubKeyHash();
    return Address.fromP2SHHash(params, Buffer.from(pubKeyHash));
  }

  /**
   * Construct an address from parameters and the hash160 form.
   */
  static fromHash(params: NetworkParameters, hash160: Buffer): Address {
    return new Address(params, params.getAddressHeader(), hash160);
  }

  /** The (big endian) 20 byte hash that is the core of a Bitcoin address. */
  getHash160(): Buffer {
    return Buffer.from(this.bytes);
  }

  /**
   * Returns true if this address is a Pay-To-Script-Hash (P2SH) address.
   */
  isP2SHAddress(): boolean {
    return this.version === this.params.getP2SHHeader();
  }

  /**
   * Returns the network parameters for this address.
   */
  getParameters(): NetworkParameters {
    return this.params;
  }

  /**
   * Given an address, examines the version byte and attempts to find a matching NetworkParameters.
   */
  static getParametersFromAddress(address: string): NetworkParameters {
    // Create a temporary instance to extract version
    const temp = new VersionedChecksummedBytes(address);
    const version = temp.getVersion();
    
    let paramsFound: NetworkParameters | null = null;
    for (const p of Networks.get()) {
      if (Address.isAcceptableVersion(p, version)) {
        paramsFound = p;
        break;
      }
    }
    if (!paramsFound) {
      throw new AddressFormatException("No network found for " + address);
    }
    return paramsFound;
  }

  /**
   * Check if a given address version is valid given the NetworkParameters.
   */
  private static isAcceptableVersion(params: NetworkParameters, version: number): boolean {
    return params.getAcceptableAddressCodes().includes(version);
  }

  /**
   * Clone the address
   */
  clone(): Address {
    return new Address(this.params, this.version, Buffer.from(this.bytes));
  }
}
