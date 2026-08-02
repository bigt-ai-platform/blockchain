package net.bigtangle.evm;

/**
 * Block-scoped context available to EVM execution via the environment opcodes
 * ({@code COINBASE}, {@code TIMESTAMP}, {@code NUMBER}, {@code GASLIMIT},
 * {@code CHAINID}, {@code DIFFICULTY}, {@code BASEFEE}, {@code BLOCKHASH}).
 *
 * <p>Defaults correspond to our PoS chain (no PoW): difficulty and baseFee are
 * zero, and block-hash lookup returns zero until wired up in Phase 3.
 */
public final class BlockContext {

	/** Provides the hash of the block at the given height, or zero if unknown. */
	@FunctionalInterface
	public interface BlockHashProvider {
		Word blockHash(long height);
	}

	private final Word coinbase;
	private final long timestamp;
	private final long number;
	private final Word difficulty;
	private final long gasLimit;
	private final int chainId;
	private final Word baseFee;
	private final BlockHashProvider blockHashProvider;

	public BlockContext(Word coinbase, long timestamp, long number, Word difficulty, long gasLimit, int chainId,
			Word baseFee, BlockHashProvider blockHashProvider) {
		this.coinbase = coinbase;
		this.timestamp = timestamp;
		this.number = number;
		this.difficulty = difficulty;
		this.gasLimit = gasLimit;
		this.chainId = chainId;
		this.baseFee = baseFee;
		this.blockHashProvider = blockHashProvider;
	}

	public static BlockContext createDefault(long timestamp, long number) {
		return new BlockContext(Word.ZERO, timestamp, number, Word.ZERO, 30_000_000L, 0, Word.ZERO, h -> Word.ZERO);
	}

	public Word getCoinbase() {
		return coinbase;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public long getNumber() {
		return number;
	}

	public Word getDifficulty() {
		return difficulty;
	}

	public long getGasLimit() {
		return gasLimit;
	}

	public int getChainId() {
		return chainId;
	}

	public Word getBaseFee() {
		return baseFee;
	}

	public Word getBlockHash(long height) {
		return blockHashProvider.blockHash(height);
	}
}
