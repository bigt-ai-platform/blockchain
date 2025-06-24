import { NetworkParameters } from './NetworkParameters';
import { VersionedChecksummedBytes } from './VersionedChecksummedBytes';
import { Utils } from './Utils';

export class DumpedPrivateKey extends VersionedChecksummedBytes {
    private compressed: boolean;

    constructor(params: NetworkParameters, keyBytes: Uint8Array, compressed: boolean) {
        // In Java, DumpedPrivateKey extends VersionedChecksummedBytes and adds a 'compressed' flag.
        // The base58 encoding includes an extra byte (0x01) if compressed.
        // For simplicity, we'll just store the keyBytes and compressed flag directly.
        // The super constructor expects a version and bytes.
        // We'll prepend the 0x01 byte if compressed for the super constructor.
        let bytesToEncode = keyBytes;
        if (compressed) {
            const tempBytes = new Uint8Array(keyBytes.length + 1);
            tempBytes.set(keyBytes, 0);
            tempBytes[keyBytes.length] = 0x01;
            bytesToEncode = tempBytes;
        }
        super(params.getDumpedPrivateKeyHeader(), bytesToEncode);
        this.compressed = compressed;
    }

    isCompressed(): boolean {
        return this.compressed;
    }

    // Override toString to match Java's DumpedPrivateKey.toString()
    toString(): string {
        // This is a simplified representation. A full implementation would involve
        // Base58 encoding with checksum.
        return Utils.HEX.encode(this.bytes);
    }
}
