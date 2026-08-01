/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.core;

import java.io.Serializable;

/*
 * Evaluation of a block, variable  in time and DAG formation and references.
 *   see SolidityState for usage of solid
 */
public class BlockEvaluation implements Serializable {

	private static final long serialVersionUID = 8388463657969339286L;

	// Hash of the block
	private Sha256Hash blockHash;
	// height to genesis block
	private long height;

	// chain length of reward block as consensus
	private long chainlength;

	// Timestamp for entry into chainlength as true, reset if flip to false
	private long chainlengthLastUpdateTime;

	// Timestamp for entry into evaluations/reception time
	private long insertTime;

	// -chainlength: conflict with chainlength
	// 0: initial.
	// -1: unsolid 1: solid for calculation
	// 2: solid
	private long solid;

	// If true, this block is confirmed by mcmc and chainlength
	private boolean confirmed;

	public BlockEvaluation() {
	}

	// deep copy constructor
	public BlockEvaluation(BlockEvaluation other) {
		setBlockHash(other.blockHash);

		setHeight(other.height);
		setChainlength(other.chainlength);
		setChainlengthLastUpdateTime(other.chainlengthLastUpdateTime);
		setInsertTime(other.insertTime);
		setSolid(other.solid);
		setConfirmed(other.confirmed);
	}

	public static BlockEvaluation buildInitial(Block block) {
		long currentTimeMillis = System.currentTimeMillis();
		return BlockEvaluation.build(block.getHash(), 0, -1, currentTimeMillis, currentTimeMillis, 0, false);
	}

	public static BlockEvaluation build(Sha256Hash blockhash, long height, long chainlength, long chainlengthLastUpdateTime,
			long insertTime, long solid, boolean confirmed) {
		BlockEvaluation blockEvaluation = new BlockEvaluation();
		blockEvaluation.setBlockHash(blockhash);

		blockEvaluation.setHeight(height);
		blockEvaluation.setChainlength(chainlength);
		blockEvaluation.setChainlengthLastUpdateTime(chainlengthLastUpdateTime);
		blockEvaluation.setInsertTime(insertTime);
		blockEvaluation.setSolid(solid);
		blockEvaluation.setConfirmed(confirmed);

		return blockEvaluation;
	}

	public Sha256Hash getBlockHash() {
		return blockHash;
	}

	public void setBlockHash(Sha256Hash blockHash) {
		this.blockHash = blockHash;
	}

	public long getHeight() {
		return height;
	}

	public void setHeight(long height) {
		this.height = height;
	}

	public long getChainlength() {
		return chainlength;
	}

	public void setChainlength(long chainlength) {
		this.chainlength = chainlength;
	}

	public long getChainlengthLastUpdateTime() {
		return chainlengthLastUpdateTime;
	}

	public void setChainlengthLastUpdateTime(long chainlengthLastUpdateTime) {
		this.chainlengthLastUpdateTime = chainlengthLastUpdateTime;
	}

	public long getInsertTime() {
		return insertTime;
	}

	public void setInsertTime(long insertTime) {
		this.insertTime = insertTime;
	}

	public long getSolid() {
		return solid;
	}

	public void setSolid(long solid) {
		this.solid = solid;
	}

	public boolean isConfirmed() {
		return confirmed;
	}

	public void setConfirmed(boolean confirmed) {
		this.confirmed = confirmed;
	}

	@Override
	public String toString() {
		return "BlockEvaluation [blockHash=" + blockHash + ", height=" + height + ", chainlength=" + chainlength
				+ " \n , chainlengthLastUpdateTime=" + chainlengthLastUpdateTime + ", insertTime=" + insertTime + ", solid="
				+ solid + "\n, confirmed=" + confirmed + "]";
	}

}
