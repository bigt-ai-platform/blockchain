/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.HashMap;

import net.bigtangle.core.Block;
import net.bigtangle.core.Utils;

public class AskTransactionResponse extends AbstractResponse {

    private String r1Hex;

    private String r2Hex;

    public static AbstractResponse create(HashMap<String, Block> result) {
        AskTransactionResponse res = new AskTransactionResponse();
        Block b1 = result.get("r1");
        Block b2 = result.get("r2");
        res.r1Hex = Utils.HEX.encode(b1.bitcoinSerialize());
        res.r2Hex = Utils.HEX.encode(b2.bitcoinSerialize());
        res.setErrorcode(0);
        return res;
    }

    public String getR1Hex() {
        return r1Hex;
    }

    public void setR1Hex(String r1Hex) {
        this.r1Hex = r1Hex;
    }

    public String getR2Hex() {
        return r2Hex;
    }

    public void setR2Hex(String r2Hex) {
        this.r2Hex = r2Hex;
    }

    public static AbstractResponse create(Block block) {
        return null;
    }

}
