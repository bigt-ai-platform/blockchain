/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;
import java.util.Map;

import net.bigtangle.core.Token;
import net.bigtangle.core.UTXO;

public class GetOutputsResponse extends AbstractResponse {

    private List<UTXO> outputs;

    private Map<String, Token> tokennames;
    
    public static GetOutputsResponse create(List<UTXO> outputs, Map<String, Token> tokennames) {
        GetOutputsResponse res = new GetOutputsResponse();
        res.outputs = outputs;
        res.tokennames = tokennames;
        return res;
    }

    public List<UTXO> getOutputs() {
        return outputs;
    }

    public Map<String, Token> getTokennames() {
        return tokennames;
    }

    public void setTokennames(Map<String, Token> tokennames) {
        this.tokennames = tokennames;
    }

    public void setOutputs(List<UTXO> outputs) {
        this.outputs = outputs;
    }
    
}
