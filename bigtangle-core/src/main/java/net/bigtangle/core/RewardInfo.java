/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

public class RewardInfo extends DataClass implements java.io.Serializable {

    private static final long serialVersionUID = 6516115233185538213L;
    private long chainlength;
    private Sha256Hash prevRewardHash;
    private Set<Sha256Hash> blocks;
    private long difficultyTargetReward;
    private Sha256Hash ordermatchingResult;
    private Sha256Hash contractResult;
    private Sha256Hash miningResult;
    
    public RewardInfo() {
    }

    public RewardInfo(Sha256Hash prevRewardHash,long difficultyTargetReward, Set<Sha256Hash> blocks, long chainlength) {
        super();
        this.prevRewardHash = prevRewardHash;
        this.difficultyTargetReward=difficultyTargetReward;
        this.blocks = blocks;
        this.chainlength = chainlength;
    }


    /**
     * The number that is one greater than the largest representable SHA-256
     * hash.
     */
    private static final BigInteger LARGEST_HASH = BigInteger.ONE.shiftLeft(256);

    /**
     * Returns the work represented by this block.<p>
     *
     * Work is defined as the number of tries needed to solve a block in the
     * average case. Consider a difficulty target that covers 5% of all possible
     * hash values. Then the work of the block will be 20. As the target gets
     * lower, the amount of work goes up.
     */
    public BigInteger getWork()  {
        BigInteger target = getDifficultyTargetAsInteger();
        return LARGEST_HASH.divide(target.add(BigInteger.ONE));
    }

    /**
     * Returns the difficulty target as a 256 bit value that can be compared to a SHA-256 hash. Inside a block the
     * target is represented using a compact form. If this form decodes to a value that is out of bounds, an exception
     * is thrown.
     */
    public BigInteger getDifficultyTargetAsInteger()  {
        return Utils.decodeCompactBits(difficultyTargetReward);
    }
    
    public static long getSerialversionuid() {
        return serialVersionUID;
    }

    public void setPrevRewardHash(Sha256Hash prevRewardHash) {
        this.prevRewardHash = prevRewardHash;
    }

    public Sha256Hash getPrevRewardHash() {
        return prevRewardHash;
    }

    public Set<Sha256Hash> getBlocks() {
        return blocks;
    }

    public void setBlocks(Set<Sha256Hash> blocks) {
        this.blocks = blocks;
    }

    public long getChainlength() {
        return chainlength;
    }

    public void setChainlength(long chainlength) {
        this.chainlength = chainlength;
    }

    
    public long getDifficultyTargetReward() {
        return difficultyTargetReward;
    }

    public void setDifficultyTargetReward(long difficultyTargetReward) {
        this.difficultyTargetReward = difficultyTargetReward;
    }
 

    public Sha256Hash getMiningResult() {
        return miningResult;
    }

    public void setMiningResult(Sha256Hash miningResult) {
        this.miningResult = miningResult;
    }

    public Sha256Hash getOrdermatchingResult() {
		return ordermatchingResult;
	}

	public void setOrdermatchingResult(Sha256Hash ordermatchingResult) {
		this.ordermatchingResult = ordermatchingResult;
	}

	public Sha256Hash getContractResult() {
		return contractResult;
	}

	public void setContractResult(Sha256Hash contractResult) {
		this.contractResult = contractResult;
	}

	public byte[] toByteArray() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream dos = new DataOutputStream(baos);
            
            dos.writeLong(chainlength);
            dos.write(prevRewardHash.getBytes());
            dos.writeInt(blocks.size());
            for (Sha256Hash bHash : blocks) 
                dos.write(bHash.getBytes());
            dos.writeLong(difficultyTargetReward);
            dos.writeBoolean(ordermatchingResult != null);
            if (ordermatchingResult != null)
                dos.write(ordermatchingResult.getBytes());
            dos.writeBoolean(contractResult != null);
            if (contractResult != null)
                dos.write(contractResult.getBytes());
            dos.writeBoolean(miningResult != null);
            if (miningResult != null)
                dos.write(miningResult.getBytes());
            
            dos.close();
        } catch (IOException e) {
            throw  new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    public RewardInfo parseChecked(byte[] buf) {
        try {
            return parse(buf);
        } catch (IOException e) {
            // Cannot happen since checked before
            throw new RuntimeException(e);
        }
    }

    public RewardInfo parse(byte[] buf) throws IOException {
        ByteArrayInputStream bain = new ByteArrayInputStream(buf);
        DataInputStream dis = new DataInputStream(bain);
        byte[] hbuf = new byte[32];
        
        RewardInfo r = new RewardInfo();
        r.chainlength = dis.readLong();
        dis.readFully(hbuf);
        r.prevRewardHash = Sha256Hash.wrap(hbuf);
        int blocksSize = dis.readInt();
        r.blocks = new HashSet<>();
        for (int i = 0; i < blocksSize; ++i) {
            hbuf = new byte[32];
            dis.readFully(hbuf);
            r.blocks.add(Sha256Hash.wrap(hbuf));
        }
        r.difficultyTargetReward = dis.readLong();
        
        boolean hasOrderMatching = dis.readBoolean();
        if (hasOrderMatching) {
            hbuf = new byte[32];
            dis.readFully(hbuf);
            r.ordermatchingResult = Sha256Hash.wrap(hbuf);
        } 
        
        boolean hasContract = dis.readBoolean();
        if (hasContract) {
            hbuf = new byte[32];
            dis.readFully(hbuf);
            r.contractResult = Sha256Hash.wrap(hbuf);
        }
        
        boolean hasMiningReward = dis.readBoolean();
        if (hasMiningReward) {
            hbuf = new byte[32];
            dis.readFully(hbuf);
            r.miningResult = Sha256Hash.wrap(hbuf);
        } 
        
        dis.close();
        bain.close();
        return r;
    }

    @Override
    public String toString() {
        return "RewardInfo  \n chainlength=" + chainlength + ", \n prevRewardHash=" + prevRewardHash
                + ", \n prevRewardDifficulty=" + difficultyTargetReward
                + ", \n referenced block size =" + blocks.size() + "]";
    }

}
