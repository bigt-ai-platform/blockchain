package net.bigtangle.response;

import net.bigtangle.core.Token;

public class GetDomainTokenResponse extends AbstractResponse {

    private Token domainNameToken;

    public Token getdomainNameToken() {
        return domainNameToken;
    }

    public void setdomainNameToken(Token domainNameToken) {
        this.domainNameToken = domainNameToken;
    }
    
    public static AbstractResponse createGetDomainBlockHashResponse(Token domainNameToken) {
        GetDomainTokenResponse res = new GetDomainTokenResponse();
        res.domainNameToken = domainNameToken;
        return res;
    }
}
