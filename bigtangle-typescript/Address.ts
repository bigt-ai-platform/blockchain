import { VersionedChecksummedBytes } from './VersionedChecksummedBytes';
import { NetworkParameters } from './NetworkParameters';
import { Networks } from './Networks';
import { WrongNetworkException } from './exception/WrongNetworkException';
import { AddressFormatException } from './exception/AddressFormatException';
import { Script } from './Script';
import { ECKey } from './ECKey';
import { Utils } from './Utils';

export class Address extends VersionedChecksummedBytes {
    public static LENGTH: number = 20;

    private params: NetworkParameters;

    constructor(params: NetworkParameters, version: number, hash160: Uint8Array);
    constructor(params: NetworkParameters, version: number, hash160: Uint8Array);
    constructor(params: NetworkParameters, hash160: Uint8Array);
    constructor(params: NetworkParameters | null, address: string);
    constructor(...args: any[]) {
        if (args.length === 3) {
            // Constructor: (params: NetworkParameters, version: number, hash160: Uint8Array)
            const [params, version, hash160] = args;
            super(version, hash160);
            this.validateParams(params);
            this.validateHash160Length(hash160);
            if (!Address.isAcceptableVersion(params, version)) {
                throw new WrongNetworkException(version, params.getAcceptableAddressCodes());
            }
            this.params = params;
        } else if (args.length === 2 && args[1] instanceof Uint8Array) {
            // Constructor: (params: NetworkParameters, hash160: Uint8Array)
            const [params, hash160] = args;
            super(params.getAddressHeader(), hash160);
            this.validateParams(params);
            this.validateHash160Length(hash160);
            this.params = params;
        } else if (args.length === 2 && typeof args[1] === 'string') {
            // Constructor: (params: NetworkParameters | null, address: string)
            const [params, address] = args;
            super(address);
            this.params = this.resolveNetworkParameters(params, address);
        } else {
            throw new Error('Invalid constructor arguments for Address');
        }
    }

    public static fromKey(params: NetworkParameters, key: ECKey): Address {
        const pubKey = key.getPubKey();
        const hash160 = Utils.sha256hash160(pubKey);
        return new Address(params, hash160);
    }

    private validateParams(params: NetworkParameters): void {
        if (!params) {
            throw new Error('params cannot be null');
        }
    }

    private validateHash160Length(hash160: Uint8Array): void {
        if (hash160.length !== Address.LENGTH) {
            throw new Error('Addresses are 160-bit hashes, so you must provide 20 bytes');
        }
    }

    private resolveNetworkParameters(params: NetworkParameters | null, address: string): NetworkParameters {
        if (params !== null) {
            return params;
        }

        const paramsFound = Networks.get().find(p => 
            Address.isAcceptableVersion(p, this.version)
        );

        if (!paramsFound) {
            throw new AddressFormatException(`No network found for ${address}`);
        }

        return paramsFound;
    }

    public static fromP2SHHash(params: NetworkParameters, hash160: Uint8Array): Address {
        try {
            return new Address(params, params.getP2SHHeader(), hash160);
        } catch (e: any) {
            throw new Error('Unexpected error: ' + e.message);
        }
    }

    public static fromP2SHScript(params: NetworkParameters, scriptPubKey: Script): Address {
        if (!scriptPubKey.isPayToScriptHash()) {
            throw new Error('Not a P2SH script');
        }
        return Address.fromP2SHHash(params, scriptPubKey.getPubKeyHash());
    }

    public static fromBase58(params: NetworkParameters | null, base58: string): Address {
        return new Address(params, base58);
    }

    public getHash160(): Uint8Array {
        return this.bytes;
    }

    public isP2SHAddress(): boolean {
        const parameters = this.getParameters();
        return parameters !== null && this.version === parameters.getP2SHHeader();
    }

    public getParameters(): NetworkParameters {
        return this.params;
    }

    public static getParametersFromAddress(address: string): NetworkParameters {
        try {
            return Address.fromBase58(null, address).getParameters();
        } catch (e) {
            throw new AddressFormatException('Invalid address format');
        }
    }

    private static isAcceptableVersion(params: NetworkParameters, version: number): boolean {
        return params.getAcceptableAddressCodes().includes(version);
    }

    public clone(): Address {
        return Object.assign(Object.create(Object.getPrototypeOf(this)), this);
    }
}
