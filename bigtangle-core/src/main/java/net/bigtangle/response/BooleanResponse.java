/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

public class BooleanResponse extends AbstractResponse {

    private Boolean  value;
 
    public static BooleanResponse create(Boolean  value) {
        BooleanResponse res = new BooleanResponse();
        res.value =  value;
        return res;
    }

    public Boolean getValue() {
        return value;
    }

    public void setValue(Boolean value) {
        this.value = value;
    }
 
    
     
}
