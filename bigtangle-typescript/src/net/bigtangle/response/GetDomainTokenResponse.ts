import { AbstractResponse } from './AbstractResponse';
import { Token } from '../core/Token';

export class GetDomainTokenResponse extends AbstractResponse {
    private domainNameToken: Token | null = null;

    public getdomainNameToken(): Token | null {
        return this.domainNameToken;
    }

    public setdomainNameToken(domainNameToken: Token | null): void {
        this.domainNameToken = domainNameToken;
    }
    
    public static createGetDomainBlockHashResponse(domainNameToken: Token): GetDomainTokenResponse {
        const res = new GetDomainTokenResponse();
        res.domainNameToken = domainNameToken;
        return res;
    }
}