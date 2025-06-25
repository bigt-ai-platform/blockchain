/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import net.bigtangle.core.PayMultiSign;

public class PayMultiSignDetailsResponse extends AbstractResponse {

    private PayMultiSign payMultiSign;

    public static AbstractResponse create(PayMultiSign payMultiSign) {
        PayMultiSignDetailsResponse res = new PayMultiSignDetailsResponse();
        res.payMultiSign = payMultiSign;
        return res;
    }

    public PayMultiSign getPayMultiSign() {
        return payMultiSign;
    }

    public void setPayMultiSign(PayMultiSign payMultiSign) {
        this.payMultiSign = payMultiSign;
    }
}
