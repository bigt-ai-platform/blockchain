/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

public class GetStringResponse extends AbstractResponse {

    private String text;

      
    public static AbstractResponse create(String text ) {
        GetStringResponse res = new GetStringResponse();
        res.text = text;
       
        return res;
    } 

    public String getText() {
        return text;
    }


    public void setText(String text) {
        this.text = text;
    }

  
}
