package net.bigtangle.core;

import net.bigtangle.params.NetworkParameters;

public enum BlockType {
	/**
	 * To add new BLOCKTYPES to implement type specific function The order can not
	 * be changed for history! enum cardinal is saved in database. It can be added
	 * new at the end of enum Type
	 */

	BLOCKTYPE_INITIAL(false, Integer.MAX_VALUE, false), // Genesis block
	BLOCKTYPE_TRANSFER(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // Default
	BLOCKTYPE_BEACON(false, NetworkParameters.MAX_REWARD_BLOCK_SIZE, false), // Rewards
	BLOCKTYPE_TOKEN_CREATION(true, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // tokenissuance
	BLOCKTYPE_USERDATA(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // User-defined-data
	BLOCKTYPE_CONTRACT_EVENT(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // Smart-contracts
	BLOCKTYPE_GOVERNANCE(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // Governance
	BLOCKTYPE_FILE(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // User-defined-file
		BLOCKTYPE_CROSSTANGLE(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // mainnet to permissioned
	BLOCKTYPE_ORDER_OPEN(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // new-order
	BLOCKTYPE_ORDER_CANCEL(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // cancel-order
	BLOCKTYPE_CONTRACTEVENT_CANCEL(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // Order execution
	BLOCKTYPE_STAKE(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // PoS staking deposit
	BLOCKTYPE_SLASHING(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false), // PoS slashing proof
	BLOCKTYPE_NFT(false, NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, false); // NFT creation / user data

	private final boolean allowCoinbaseTransaction;
	private final int maxSize;
	private final boolean requiresCalculation;

	BlockType(boolean allowCoinbaseTransaction, int maxSize, boolean requiresCalculation) {
		this.allowCoinbaseTransaction = allowCoinbaseTransaction;
		this.maxSize = maxSize;
		this.requiresCalculation = requiresCalculation;
	}

	public boolean allowCoinbaseTransaction() {
		return allowCoinbaseTransaction;
	}

	public int getMaxBlockSize() {
		return maxSize;
	}

	public boolean requiresCalculation() {
		return requiresCalculation;
	}
}
