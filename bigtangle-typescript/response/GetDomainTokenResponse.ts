import { Token } from '../Token';

export class GetDomainTokenResponse {
    private domainNameToken: Token;

    constructor(domainNameToken: Token) {
        this.domainNameToken = domainNameToken;
    }

    getdomainNameToken(): Token {
        return this.domainNameToken;
    }

    setdomainNameToken(domainNameToken: Token): void {
        this.domainNameToken = domainNameToken;
    }
}
