import { Token } from './Token';
import { MultiSignAddress } from './MultiSignAddress';

export class TokenInfo {
    private token: Token;
    private multiSignAddresses: MultiSignAddress[];

    constructor(token?: Token, multiSignAddresses?: MultiSignAddress[]) {
        this.token = token;
        this.multiSignAddresses = multiSignAddresses || [];
    }

    getToken(): Token {
        return this.token;
    }

    setToken(token: Token): void {
        this.token = token;
    }

    getMultiSignAddresses(): MultiSignAddress[] {
        return this.multiSignAddresses;
    }

    setMultiSignAddresses(multiSignAddresses: MultiSignAddress[]): void {
        this.multiSignAddresses = multiSignAddresses;
    }
}
