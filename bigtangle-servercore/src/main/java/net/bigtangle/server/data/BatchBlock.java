/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.server.data;

import java.io.Serial;
import java.util.Date;

import net.bigtangle.core.Sha256Hash;

public class BatchBlock implements java.io.Serializable {

    @Serial
    private static final long serialVersionUID = -6405699071408618795L;

    private Sha256Hash hash;
    
    private byte[] block;
    
    private Date insertTime;

    public Sha256Hash getHash() {
        return hash;
    }

    public void setHash(Sha256Hash hash) {
        this.hash = hash;
    }

    public byte[] getBlock() {
        return block;
    }

    public void setBlock(byte[] block) {
        this.block = block;
    }

    public Date getInsertTime() {
        return insertTime;
    }

    public void setInsertTime(Date insertTime) {
        this.insertTime = insertTime;
    }
}
