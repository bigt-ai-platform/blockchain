/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

/**
 *
 */
public class SpentBlockData extends SpentBlock {

	public SpentBlockData(Sha256Hash blockhash, boolean spent, boolean confirmed, Sha256Hash spenderBlockHash) {
		this.setBlockHash(blockhash);
		this.setSpent(spent);
		this.setSpenderBlockHash(spenderBlockHash);

		this.setConfirmed(confirmed);

	}

}
