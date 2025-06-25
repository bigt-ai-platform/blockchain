import { Buffer } from 'buffer';
import { Utils } from './Utils';
import { NetworkParameters } from './NetworkParameters';
import { Sha256Hash } from './Sha256Hash';

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
        const bytes = Utils.base58ToBytes(base58);
        if (bytes.length !== 25) {
            throw new Error('Address has wrong length');
        }

        const version = bytes[0] & 0xFF;
        if (!Address.isAcceptableVersion(params, version)) {
            throw new Error('Wrong network version');
        }

        const checksum = Sha256Hash.hashTwice(Buffer.from(bytes.slice(0, 21))).toBuffer().slice(0, 4);
        if (!Buffer.from(bytes.slice(21, 25)).equals(checksum)) {
            throw new Error('Checksum does not validate');
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
}
