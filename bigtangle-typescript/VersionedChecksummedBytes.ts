import { Base58 } from './utils/Base58';
import { Utils } from './Utils';
import { AddressFormatException } from './exception/AddressFormatException';

export class VersionedChecksummedBytes {
    protected version: number;
    protected bytes: Uint8Array;

    constructor(version: number, bytes: Uint8Array);
    constructor(address: string);
    constructor(...args: any[]) {
        if (args.length === 2) {
            // Constructor: (version: number, bytes: Uint8Array)
            this.version = args[0];
            this.bytes = args[1];
        } else if (args.length === 1 && typeof args[0] === 'string') {
            // Constructor: (address: string)
            const decoded = Base58.decode(args[0]);
            if (decoded.length < 5) {
                throw new AddressFormatException("Input too short");
            }
            
            this.version = decoded[0];
            this.bytes = decoded.slice(1, decoded.length - 4);
            
            const checksum = decoded.slice(decoded.length - 4);
            const payload = decoded.slice(0, decoded.length - 4);
            const calculatedChecksum = Utils.doubleDigest(payload).slice(0, 4);
            
            if (!Utils.arraysEqual(checksum, calculatedChecksum)) {
                throw new AddressFormatException("Checksum does not validate");
            }
        } else {
            throw new Error('Invalid constructor arguments for VersionedChecksummedBytes');
        }
    }

    public getVersion(): number {
        return this.version;
    }

    public getBytes(): Uint8Array {
        return this.bytes;
    }

    public toBase58(): string {
        const payload = new Uint8Array(this.bytes.length + 1);
        payload[0] = this.version;
        payload.set(this.bytes, 1);
        
        const checksum = Utils.doubleDigest(payload).slice(0, 4);
        const result = new Uint8Array(payload.length + checksum.length);
        result.set(payload);
        result.set(checksum, payload.length);
        
        return Base58.encode(result);
    }

    public clone(): VersionedChecksummedBytes {
        return Object.assign(Object.create(Object.getPrototypeOf(this)), this);
    }

    public toString(): string {
        return this.toBase58();
    }
}
