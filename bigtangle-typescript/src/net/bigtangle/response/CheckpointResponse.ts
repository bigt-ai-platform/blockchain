import { AbstractResponse } from './AbstractResponse';
import { Sha256Hash } from '../core/Sha256Hash';
import { TokensumsMap } from '../core/TokensumsMap';

export class CheckpointResponse extends AbstractResponse {
    private tokensumsMapHash: Sha256Hash | null = null;

    public static create(tokensumsMap: TokensumsMap): CheckpointResponse {
        const res = new CheckpointResponse();
        res.tokensumsMapHash = tokensumsMap.hash();
        return res;
    }

    public getTokensumsMapHash(): Sha256Hash | null {
        return this.tokensumsMapHash;
    }

    public setTokensumsMapHash(tokensumsMapHash: Sha256Hash | null): void {
        this.tokensumsMapHash = tokensumsMapHash;
    }
}
