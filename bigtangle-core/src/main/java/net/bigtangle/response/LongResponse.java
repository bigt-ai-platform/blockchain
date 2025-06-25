/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

public class LongResponse extends AbstractResponse {

    private Long  value;
 
    public static LongResponse create(Long  value) {
        LongResponse res = new LongResponse();
        res.value =  value;
        return res;
    }

    public Long getValue() {
        return value;
    }

    public void setValue(Long value) {
        this.value = value;
    }
 
    
     
}
