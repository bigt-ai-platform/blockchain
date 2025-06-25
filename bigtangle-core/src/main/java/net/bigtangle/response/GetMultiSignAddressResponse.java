/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

import java.util.List;

import net.bigtangle.core.MultiSignAddress;

public class GetMultiSignAddressResponse extends AbstractResponse {

    public static GetMultiSignAddressResponse create(List<MultiSignAddress> list) {
        GetMultiSignAddressResponse res = new GetMultiSignAddressResponse();
        res.list = list;
        return res;
    }

    private List<MultiSignAddress> list;

    public List<MultiSignAddress> getList() {
        return list;
    }

    public void setList(List<MultiSignAddress> list) {
        this.list = list;
    }
}
