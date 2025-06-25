import { Token } from '../Token';

export class GetTokensResponse {
    private tokens: Token[];

    constructor(tokens: Token[]) {
        this.tokens = tokens;
    }

    getTokens(): Token[] {
        return this.tokens;
    }

    setTokens(tokens: Token[]): void {
        this.tokens = tokens;
    }
}
