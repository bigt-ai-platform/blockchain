/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

import java.util.List;

import net.bigtangle.core.MultiSign;

public class MultiSignResponse extends AbstractResponse {

    public List<MultiSign> multiSigns;
    public int signCount;

    public List<MultiSign> getMultiSigns() {
        return multiSigns;
    }

    public void setMultiSigns(List<MultiSign> multiSigns) {
        this.multiSigns = multiSigns;
    }

    public static AbstractResponse createMultiSignResponse(List<MultiSign> multiSigns) {
        MultiSignResponse res = new MultiSignResponse();
        res.multiSigns = multiSigns;
        return res;
    }

    public static AbstractResponse createMultiSignResponse(int signCount) {
        MultiSignResponse res = new MultiSignResponse();
        res.signCount = signCount;
        return res;
    }

    public int getSignCount() {
        return signCount;
    }

    public void setSignCount(int signCount) {
        this.signCount = signCount;
    }
}
