/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

import java.util.ArrayList;
import java.util.List;

import net.bigtangle.core.MultiSignBy;

public class MultiSignByRequest implements java.io.Serializable {

    private static final long serialVersionUID = -9174203521292918071L;
    
    private List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();

    public List<MultiSignBy> getMultiSignBies() {
        return multiSignBies;
    }

    public void setMultiSignBies(List<MultiSignBy> multiSignBies) {
        this.multiSignBies = multiSignBies;
    }
    
    public static MultiSignByRequest create(List<MultiSignBy> multiSignBies) {
        MultiSignByRequest res = new MultiSignByRequest();
        res.multiSignBies = multiSignBies;
        return res;
    }
}
