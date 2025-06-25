/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;

import net.bigtangle.core.MultiSignAddress;

public class PermissionedAddressesResponse extends AbstractResponse {

    private List<MultiSignAddress> multiSignAddresses;
    
    private String domainName;
    
    private boolean isRootPermissioned;

    public static AbstractResponse create(String domainName, boolean isRootPermissioned, List<MultiSignAddress> multiSignAddresses) {
        PermissionedAddressesResponse res = new PermissionedAddressesResponse();
        res.domainName = domainName;
        res.isRootPermissioned = isRootPermissioned;
        res.multiSignAddresses = multiSignAddresses;
        return res;
    }

    public List<MultiSignAddress> getMultiSignAddresses() {
        return multiSignAddresses;
    }

    public void setMultiSignAddresses(List<MultiSignAddress> multiSignAddresses) {
        this.multiSignAddresses = multiSignAddresses;
    }

    public boolean isRootPermissioned() {
        return isRootPermissioned;
    }

    public void setRootPermissioned(boolean isRootPermissioned) {
        this.isRootPermissioned = isRootPermissioned;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }
}
