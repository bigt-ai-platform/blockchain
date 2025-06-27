import { ECPoint } from '../core/ECPoint';

export class ECIESPublicKeyEncoder {
    public getEncoded(publicKey: ECPoint): Uint8Array {
        return publicKey.encode(false);
    }
}
