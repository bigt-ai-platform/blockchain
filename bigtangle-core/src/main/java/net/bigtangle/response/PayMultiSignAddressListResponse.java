/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;

import net.bigtangle.core.PayMultiSignAddress;

public class PayMultiSignAddressListResponse extends AbstractResponse {

    private List<PayMultiSignAddress> payMultiSignAddresses;

    public static AbstractResponse create(List<PayMultiSignAddress> payMultiSignAddresses) {
        PayMultiSignAddressListResponse res = new PayMultiSignAddressListResponse();
        res.payMultiSignAddresses = payMultiSignAddresses;
        return res;
    }

    public List<PayMultiSignAddress> getPayMultiSignAddresses() {
        return payMultiSignAddresses;
    }

    public void setPayMultiSignAddresses(List<PayMultiSignAddress> payMultiSignAddresses) {
        this.payMultiSignAddresses = payMultiSignAddresses;
    }
}
