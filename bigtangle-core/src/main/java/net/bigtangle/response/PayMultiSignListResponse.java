/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;

import net.bigtangle.core.PayMultiSignExt;

public class PayMultiSignListResponse extends AbstractResponse {

    private List<PayMultiSignExt> payMultiSigns;

    public static AbstractResponse create(List<PayMultiSignExt> payMultiSigns) {
        PayMultiSignListResponse res = new PayMultiSignListResponse();
        res.payMultiSigns = payMultiSigns;
        return res;
    }

    public List<PayMultiSignExt> getPayMultiSigns() {
        return payMultiSigns;
    }
}
