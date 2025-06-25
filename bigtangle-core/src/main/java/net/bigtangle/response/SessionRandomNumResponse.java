/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

public class SessionRandomNumResponse extends AbstractResponse {

    private String verifyHex;

    public static SessionRandomNumResponse create(String verifyHex) {
        SessionRandomNumResponse res = new SessionRandomNumResponse();
        res.verifyHex = verifyHex;
        return res;
    }

    public String getVerifyHex() {
        return verifyHex;
    }

    public void setVerifyHex(String verifyHex) {
        this.verifyHex = verifyHex;
    }

}
