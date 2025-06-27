import { DataClass } from './DataClass';
import { Token } from './Token';
import { MultiSignAddress } from './MultiSignAddress';
import { Json } from '../utils/Json';

export class TokenInfo extends DataClass {
    private token: Token | null = null;
    private multiSignAddresses: MultiSignAddress[] = [];

    constructor() {
        super();
        this.multiSignAddresses = [];
    }

    public toByteArray(): Uint8Array {
        try {
            const jsonStr = Json.jsonmapper().writeValueAsString(this);
            return new TextEncoder().encode(jsonStr);
        } catch (e: any) {
            throw new Error(e);
        } 
    }

    public parse(buf: Uint8Array): TokenInfo {
        const jsonStr = new TextDecoder('utf-8').decode(buf);
        return Json.jsonmapper().readValue(jsonStr, TokenInfo);
    }

    public parseChecked(buf: Uint8Array): TokenInfo {
        const jsonStr = new TextDecoder('utf-8').decode(buf);
        try {
            return Json.jsonmapper().readValue(jsonStr, TokenInfo);
        } catch (e: any) {
            throw new Error(e);
        }
    }

    public getToken(): Token | null {
        return this.token;
    }

    public setToken(token: Token | null): void {
        this.token = token;
    }

    public getMultiSignAddresses(): MultiSignAddress[] {
        return this.multiSignAddresses;
    }

    public setMultiSignAddresses(multiSignAddresses: MultiSignAddress[]): void {
        this.multiSignAddresses = multiSignAddresses;
    }

    public toString(): string {
        return `TokenInfo [tokens=${this.token}, multiSignAddresses=${this.multiSignAddresses}]`;
    }
}