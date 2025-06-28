import { Buffer } from 'buffer';
import { Utils } from './Utils.js';
import { NetworkParameters } from './NetworkParameters.js';
import { Sha256Hash } from './Sha256Hash.js';
import { AddressFormatException } from '../exception/AddressFormatException.js';
import { WrongNetworkException } from '../exception/WrongNetworkException.js';

export class Address {
    private readonly params: NetworkParameters;
    private readonly version: number;
    private readonly hash160: Buffer;

    constructor(params: NetworkParameters, version: number, hash160: Buffer) {
        this.params = params;
        this.version = version;
        this.hash160 = hash160;
    }

    public static fromP2SHHash(params: NetworkParameters, hash160: Buffer): Address {
        return new Address(params, params.getP2SHHeader(), hash160);
    }

    public static fromP2PKH(params: NetworkParameters, hash160: Buffer): Address {
        return new Address(params, params.getAddressHeader(), hash160);
    }

    public getVersion(): number {
        return this.version;
    }

    public getHash160(): Buffer {
        return this.hash160;
    }

    public isP2SHAddress(): boolean {
        return this.version === this.params.getP2SHHeader();
    }

    public static fromBase58(params: NetworkParameters, base58: string): Address {
        let bytes: Uint8Array;
        try {
            bytes = Utils.base58ToBytes(base58);
        } catch (e) {
            throw new AddressFormatException(e instanceof Error ? e.message : String(e));
        }
        if (bytes.length !== 25) {
            throw new AddressFormatException('Address has wrong length');
        }

        const version = bytes[0] & 0xFF;
        if (!Address.isAcceptableVersion(params, version)) {
            throw new WrongNetworkException(version, params.getAcceptableAddressCodes());
        }

        // Use .toBuffer() to get Buffer, then .subarray(0, 4) for checksum
        const checksum = Sha256Hash.hashTwice(Buffer.from(bytes.slice(0, 21))).toBuffer().subarray(0, 4);
        if (!Buffer.from(bytes.slice(21, 25)).equals(checksum)) {
            throw new AddressFormatException('Checksum does not validate');
        }

        return new Address(params, version, Buffer.from(bytes.slice(1, 21)));
    }

    public toBase58(): string {
        const bytes = Buffer.alloc(21);
        bytes[0] = this.version;
        this.hash160.copy(bytes, 1, 0, 20);

        const checksum = Sha256Hash.hashTwice(bytes).toBuffer().slice(0, 4);
        return Utils.bytesToBase58(Buffer.concat([bytes, checksum]));
    }

    private static isAcceptableVersion(params: NetworkParameters, version: number): boolean {
        return params.getAcceptableAddressCodes().includes(version);
    }

    public toString(): string {
        return this.toBase58();
    }

    public equals(other: Address): boolean {
        return this.params === other.params && 
               this.version === other.version && 
               this.hash160.equals(other.hash160);
    }
  compareTo(other: Address): number {
    const thisBytes = this.getHash160();
    const otherBytes = other.getHash160();
    for (let i = 0; i < Math.min(thisBytes.length, otherBytes.length); i++) {
        if (thisBytes[i] !== otherBytes[i]) {
            return thisBytes[i] - otherBytes[i];
        }
    }
    return thisBytes.length - otherBytes.length;
  }
}