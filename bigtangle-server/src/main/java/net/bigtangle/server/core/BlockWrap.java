/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.server.core;

import java.io.IOException;
import java.util.HashSet;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.data.OrderExecutionResult;

/**
 * Wraps a {@link Block} object with extra data from the db
 */
public class BlockWrap {
	protected Block block;
	protected BlockEvaluation blockEvaluation;
	protected BlockMCMC mcmc;
	protected NetworkParameters params;

	protected BlockWrap() {
		super();
	}

	public BlockWrap(Block block, BlockEvaluation blockEvaluation, BlockMCMC mcmc, NetworkParameters params) {
		super();
		this.block = block;
		this.blockEvaluation = blockEvaluation;
		if (mcmc == null)
			this.mcmc = BlockMCMC.defaultBlockMCMC(blockEvaluation.getBlockHash());
		else {
			this.mcmc = mcmc;
		}
		this.params = params;
	}

	public Block getBlock() {
		return block;
	}

	public BlockEvaluation getBlockEvaluation() {
		return blockEvaluation;
	}

	public BlockMCMC getMcmc() {
		return mcmc;
	}

	public void setMcmc(BlockMCMC mcmc) {
		this.mcmc = mcmc;
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
		return block.toString() + " \n" + blockEvaluation.toString() + " \n" + mcmc.toString() + " \n";
	}

	@Override
	public int hashCode() {
		return getBlock().hashCode();
	}

	public Sha256Hash getBlockHash() {
		return block.getHash();
	}

	public HashSet<ConflictCandidate> toConflictCandidates() {
		HashSet<ConflictCandidate> blockConflicts = new HashSet<>();

		// Dynamic conflicts: conflicting transaction outpoints
		this.getBlock().getTransactions().stream().flatMap(t -> t.getInputs().stream()).filter(in -> !in.isCoinBase())
				.map(in -> ConflictCandidate.fromTransactionOutpoint(this, in.getOutpoint()))
				.forEach(c -> blockConflicts.add(c));

		addTypeSpecificConflictCandidates(blockConflicts);

		return blockConflicts;
	}

	/*
	 * This TypeSpecific will be added, if it have to check more than the transactions
	 */
	private void addTypeSpecificConflictCandidates(HashSet<ConflictCandidate> blockConflicts) {
		switch (this.getBlock().getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE:
			break;
		case BLOCKTYPE_FILE:
			break;
		case BLOCKTYPE_GOVERNANCE:
			break;
		case BLOCKTYPE_INITIAL:
			break;
		case BLOCKTYPE_REWARD:
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
		case BLOCKTYPE_TRANSFER:
			break;
		case BLOCKTYPE_USERDATA:
			break;
		case BLOCKTYPE_CONTRACT_EVENT:
			break;
		case BLOCKTYPE_CONTRACT_EXECUTE:
			try {
				ContractExecutionResult result = new ContractExecutionResult().parse(block.getTransactions().get(0).getData());
				blockConflicts.add(ConflictCandidate.fromContractExecute(this, result));

			} catch (IOException e) {
				// Cannot happen since any blocks added already were checked.
				throw new RuntimeException(e);
			}
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			try {
				OrderExecutionResult result = new OrderExecutionResult().parse(block.getTransactions().get(0).getData());
				blockConflicts.add(ConflictCandidate.fromOrderExecute(this, result));

			} catch (IOException e) {
				// Cannot happen since any blocks added already were checked.
				throw new RuntimeException(e);
			}
			break;
		case BLOCKTYPE_ORDER_OPEN:
			break;
		case BLOCKTYPE_ORDER_CANCEL:
			break;
		case BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;

		default:
			throw new RuntimeException("Blocktype not implemented!");

		}
	}
}
