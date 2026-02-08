/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.server.data;

public class ChainBlockQueue {

    private byte[] hash;
    private byte[] block;
    private long inserttime;
    private long chainlength;
    private boolean orphan;

    public ChainBlockQueue(byte[] hash, byte[] block, long chainlength, boolean orphan, long inserttime) {
        super();
        this.hash = hash;
        this.block = block;
        this.inserttime = inserttime;
        this.chainlength = chainlength;
        this.orphan = orphan;
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

    public long getChainlength() {
        return chainlength;
    }

    public void setChainlength(long chainlength) {
        this.chainlength = chainlength;
    }

    public boolean isOrphan() {
        return orphan;
    }

    public void setOrphan(boolean orphan) {
        this.orphan = orphan;
    }

    @Override
    public String toString() {
        return "  chainlength= " + chainlength    +   ", orphan=" + orphan  ;
    }

}
