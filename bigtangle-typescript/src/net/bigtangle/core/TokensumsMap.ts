import { DataClass } from './DataClass';
import { Tokensums } from './Tokensums';
import { Sha256Hash } from './Sha256Hash';
import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class TokensumsMap extends DataClass {
    private tokensumsMap: Map<string, Tokensums>;

    constructor() {
        super();
        this.tokensumsMap = new Map<string, Tokensums>();
    }

    public hash(): Sha256Hash {
        return Sha256Hash.of(this.toByteArray());
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream(baos);
        try {
            dos.write(super.toByteArray());
            // Sort keys to ensure consistent serialization
            const sortedKeys = Array.from(this.tokensumsMap.keys()).sort();
            for (const key of sortedKeys) {
                Utils.writeNBytesString(dos, key);
                dos.write(this.tokensumsMap.get(key)!.toByteArray());
            }
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public getTokensumsMap(): Map<string, Tokensums> {
        return this.tokensumsMap;
    }

    public setTokensumsMap(tokensumsMap: Map<string, Tokensums>): void {
        this.tokensumsMap = tokensumsMap;
    }
}