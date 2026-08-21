/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.server.core;

import net.bigtangle.core.*;
import net.bigtangle.params.NetworkParameters;

import java.io.IOException;
import java.util.HashSet;

/**
 * Wraps a {@link Block} object with extra data from the db
 */
public class BlockWrap {
	protected Block block;
	protected BlockEvaluation blockEvaluation;
	protected NetworkParameters params;

	/** Lazy cache of {@link #toConflictCandidates()} (block is immutable once loaded). */
	protected volatile HashSet<ConflictCandidate> conflictCandidatesCache;

	protected BlockWrap() {
		super();
	}

	public BlockWrap(Block block, BlockEvaluation blockEvaluation, NetworkParameters params) {
		super();
		this.block = block;
		this.blockEvaluation = blockEvaluation;
		this.params = params;
	}

	public Block getBlock() {
		return block;
	}

	public BlockEvaluation getBlockEvaluation() {
		return blockEvaluation;
	}

	public NetworkParameters getParams() {
		return params;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		return getBlock().equals(((BlockWrap) o).getBlock());
	}

	@Override
	public String toString() {
		return block.toString() + " \n" + blockEvaluation.toString() + " \n";
	}

	@Override
	public int hashCode() {
		return getBlock().hashCode();
	}

	public Sha256Hash getBlockHash() {
		return block.getHash();
	}

	public HashSet<ConflictCandidate> toConflictCandidates() {
		HashSet<ConflictCandidate> cached = conflictCandidatesCache;
		if (cached != null) {
			return cached;
		}
		HashSet<ConflictCandidate> blockConflicts = new HashSet<>();

		// Dynamic conflicts: conflicting transaction outpoints
		this.getBlock().getTransactions().stream().flatMap(t -> t.getInputs().stream()).filter(in -> !in.isCoinBase())
				.map(in -> ConflictCandidate.fromTransactionOutpoint(this, in.getOutpoint()))
				.forEach(blockConflicts::add);

		addTypeSpecificConflictCandidates(blockConflicts);

		conflictCandidatesCache = blockConflicts;
		return blockConflicts;
	}

	/*
	 * This TypeSpecific will be added, if it have to check more than the transactions
	 */
	private void addTypeSpecificConflictCandidates(HashSet<ConflictCandidate> blockConflicts) {
		switch (this.getBlock().getBlockType()) {
			case BLOCKTYPE_CROSSTANGLE, BLOCKTYPE_FILE, BLOCKTYPE_GOVERNANCE, BLOCKTYPE_INITIAL, BLOCKTYPE_USERDATA,
				 BLOCKTYPE_TRANSFER, BLOCKTYPE_CONTRACT_EVENT, BLOCKTYPE_ORDER_OPEN, BLOCKTYPE_ORDER_CANCEL,
				 BLOCKTYPE_CONTRACTEVENT_CANCEL, BLOCKTYPE_STAKE, BLOCKTYPE_SLASHING, BLOCKTYPE_EXIT,
				 BLOCKTYPE_EVM_DEPLOY, BLOCKTYPE_EVM_CALL:
			break;
			case BLOCKTYPE_BEACON:
			// Dynamic conflicts: mining rewards spend the previous reward
			RewardInfo rewardInfo = new RewardInfo().parseChecked(this.getBlock().getTransactions().get(0).getData());
			blockConflicts.add(ConflictCandidate.fromReward(this, rewardInfo));
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			// Dynamic conflicts: tokens of same id and index conflict
			try {
				TokenInfo tokenInfo = new TokenInfo().parse(this.getBlock().getTransactions().get(0).getData());
				blockConflicts.add(ConflictCandidate.fromToken(this, tokenInfo.getToken()));
				if (tokenInfo.getToken().isTokenDomainname()) {
					blockConflicts.add(ConflictCandidate.fromDomainToken(this, tokenInfo.getToken()));
				}
			} catch (IOException e) {
				// Cannot happen since any blocks added already were checked.
				throw new RuntimeException(e);
			}
			break;
			default:
			throw new RuntimeException("Blocktype not implemented!");

		}
	}
}
