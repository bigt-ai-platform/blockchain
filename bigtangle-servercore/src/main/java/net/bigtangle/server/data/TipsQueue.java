/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.server.data;

public class TipsQueue {

    private byte[] hash;
    private byte[] block;
    private long inserttime;
    private long height;
 

    public TipsQueue(byte[] hash, byte[] block, long height,   long inserttime) {
        super();
        this.hash = hash;
        this.block = block;
        this.inserttime = inserttime;
        this.height = height;
      
    }

    public byte[] getHash() {
        return hash;
    }

    public void setHash(byte[] hash) {
        this.hash = hash;
    }

    public byte[] getBlock() {
        return block;
    }

    public void setBlock(byte[] block) {
        this.block = block;
    }

    public long getInserttime() {
        return inserttime;
    }

    public void setInserttime(long inserttime) {
        this.inserttime = inserttime;
    }

    public long getHeight() {
        return height;
    }

    public void setHeight(long height) {
        this.height = height;
    }

    
 
    @Override
    public String toString() {
        return "  height= " + height    
                + ", inserttime= " + inserttime ;
    }

}
