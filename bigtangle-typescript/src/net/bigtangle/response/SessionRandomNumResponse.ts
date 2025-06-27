import { AbstractResponse } from './AbstractResponse';

export class SessionRandomNumResponse extends AbstractResponse {
    private verifyHex: string | null = null;

    public static create(verifyHex: string): SessionRandomNumResponse {
        const res = new SessionRandomNumResponse();
        res.verifyHex = verifyHex;
        return res;
    }

    public getVerifyHex(): string | null {
        return this.verifyHex;
    }

    public setVerifyHex(verifyHex: string | null): void {
        this.verifyHex = verifyHex;
    }
}
