/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TokensumsMap;

public class CheckpointResponse extends AbstractResponse {

    private Sha256Hash  tokensumsMapHash;
 
    public static CheckpointResponse create(TokensumsMap  tokensumsMap) {
        CheckpointResponse res = new CheckpointResponse();
        res.tokensumsMapHash =  tokensumsMap.hash();
        return res;
    }

    public Sha256Hash getTokensumsMapHash() {
        return tokensumsMapHash;
    }

    public void setTokensumsMapHash(Sha256Hash tokensumsMapHash) {
        this.tokensumsMapHash = tokensumsMapHash;
    }

   
    
     
}
