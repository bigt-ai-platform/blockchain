package net.bigtangle.server.data;

import net.bigtangle.core.Sha256Hash;

public class DepthAndWeight {
    private Sha256Hash blockHash;
    private  long weight;
    private long depth;
    
    public DepthAndWeight(Sha256Hash blockHash, long weight, long depth) {
        super();
        this.blockHash = blockHash;
        this.weight = weight;
        this.depth = depth;
    }
    public Sha256Hash getBlockHash() {
        return blockHash;
    }
    public void setBlockHash(Sha256Hash blockHash) {
        this.blockHash = blockHash;
    }
    public long getWeight() {
        return weight;
    }
    public void setWeight(long weight) {
        this.weight = weight;
    }
    public long getDepth() {
        return depth;
    }
    public void setDepth(long depth) {
        this.depth = depth;
    }

}
