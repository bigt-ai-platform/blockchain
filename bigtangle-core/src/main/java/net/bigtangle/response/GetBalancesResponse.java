/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;
import java.util.Map;

import net.bigtangle.core.Coin;
import net.bigtangle.core.Token;
import net.bigtangle.core.UTXO;

public class GetBalancesResponse extends AbstractResponse {

    private List<UTXO> outputs;

    private List<Coin> balance;

    private Map<String, Token> tokennames;
    
    public static AbstractResponse create(List<Coin> coinbalance, List<UTXO> outputs, Map<String, Token> tokennames ) {
        GetBalancesResponse res = new GetBalancesResponse();
        res.outputs = outputs;
        res.balance = coinbalance;
        res.tokennames = tokennames;
        return res;
    }

    public List<UTXO> getOutputs() {
        return outputs;
    }

    public List<Coin> getBalance() {
        return balance;
        
    }

    public Map<String, Token> getTokennames() {
        return tokennames;
    }

    public void setTokennames(Map<String, Token> tokennames) {
        this.tokennames = tokennames;
    }
}
