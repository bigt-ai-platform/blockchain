import { AbstractResponse } from './AbstractResponse';

export class SettingResponse extends AbstractResponse {
    private version: string | null = null;

    public static create(version: string): SettingResponse {
        const res = new SettingResponse();
        res.version = version;
        return res;
    }

    public getVersion(): string | null {
        return this.version;
    }

    public setVersion(version: string | null): void {
        this.version = version;
    }
}
