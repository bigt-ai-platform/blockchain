/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.bigtangle.utils.Json;

public class TokenInfo extends DataClass implements java.io.Serializable {

    // TODO drop string from everywhere, stop using Sha256Hash.wrap, instead use
    // Sha256Hash, stop using jsonserialization!

    private static final long serialVersionUID = 1554582498768357964L;

    private Token token;
    private List<MultiSignAddress> multiSignAddresses;

    public byte[] toByteArray() {
        try {
            String jsonStr = Json.jsonmapper().writeValueAsString(this);
            return jsonStr.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } 
    }

    public TokenInfo parse(byte[] buf) throws IOException {
        String jsonStr = new String(buf);
        return Json.jsonmapper().readValue(jsonStr, TokenInfo.class);
    }

    //already check and is not allowed to have IOException
    public TokenInfo parseChecked(byte[] buf) {
        String jsonStr = new String(buf);
        try {
            return Json.jsonmapper().readValue(jsonStr, TokenInfo.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Token getToken() {
        return token;
    }

    public void setToken(Token tokens) {
        this.token = tokens;
    }

    public List<MultiSignAddress> getMultiSignAddresses() {
        return multiSignAddresses;
    }

    public void setMultiSignAddresses(List<MultiSignAddress> multiSignAddresses) {
        this.multiSignAddresses = multiSignAddresses;
    }

    public TokenInfo() {
        this.multiSignAddresses = new ArrayList<>();
    }

    @Override
    public String toString() {
        return "TokenInfo [tokens=" + token + ", multiSignAddresses=" + multiSignAddresses + "]";
    }
}
