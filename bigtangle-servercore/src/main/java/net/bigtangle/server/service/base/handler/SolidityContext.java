package net.bigtangle.server.service.base.handler;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.server.service.base.ServiceBase;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Context handed to a {@link BlockTypeHandler}. It exposes everything the
 * extracted per-type methods need from the base service hierarchy, without
 * forcing handlers to extend it.
 *
 * <p>{@code base()} gives access to the shared helpers (reward/token/UTXO
 * utilities) that still live on {@link ServiceBase} and are {@code protected} -
 * handlers must live in the same package or go through the accessor methods
 * the base exposes. For Phase 0 the handlers are kept minimal, so most need
 * only the block, store and flags shown here.
 */
public final class SolidityContext {

	private final Block block;
	private final BlockStoreInterface store;
	private final long height;
	private final boolean throwExceptions;
	private final boolean confirmation;
	private final long chainlength;
	private final Sha256Hash blockHash;
	private final ServiceBase base;

	private SolidityContext(Builder b) {
		this.block = b.block;
		this.store = b.store;
		this.height = b.height;
		this.throwExceptions = b.throwExceptions;
		this.confirmation = b.confirmation;
		this.chainlength = b.chainlength;
		this.blockHash = b.blockHash;
		this.base = b.base;
	}

	public Block block() {
		return block;
	}

	public BlockStoreInterface store() {
		return store;
	}

	public long height() {
		return height;
	}

	public boolean throwExceptions() {
		return throwExceptions;
	}

	public boolean confirmation() {
		return confirmation;
	}

	public long chainlength() {
		return chainlength;
	}

	public Sha256Hash blockHash() {
		return blockHash;
	}

	public ServiceBase base() {
		return base;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private Block block;
		private BlockStoreInterface store;
		private long height;
		private boolean throwExceptions;
		private boolean confirmation = true;
		private long chainlength;
		private Sha256Hash blockHash;
		private ServiceBase base;

		public Builder block(Block v) { this.block = v; return this; }
		public Builder store(BlockStoreInterface v) { this.store = v; return this; }
		public Builder height(long v) { this.height = v; return this; }
		public Builder throwExceptions(boolean v) { this.throwExceptions = v; return this; }
		public Builder confirmation(boolean v) { this.confirmation = v; return this; }
		public Builder chainlength(long v) { this.chainlength = v; return this; }
		public Builder blockHash(Sha256Hash v) { this.blockHash = v; return this; }
		public Builder base(ServiceBase v) { this.base = v; return this; }

		public SolidityContext build() {
			return new SolidityContext(this);
		}
	}
}
