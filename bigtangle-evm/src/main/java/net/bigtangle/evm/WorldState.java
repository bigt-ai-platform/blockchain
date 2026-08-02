package net.bigtangle.evm;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The EVM world state: accounts keyed by address plus per-account contract
 * storage. Mutable; use {@link #copy()} to snapshot for deterministic
 * rollback of failed calls/transactions.
 */
public final class WorldState {

	private final TreeMap<Address, EVMAccount> accounts = new TreeMap<>();
	private final Map<Address, EVMStorage> storage = new HashMap<>();

	public EVMAccount getAccount(Address address) {
		return accounts.get(address);
	}

	public EVMAccount getOrCreateAccount(Address address) {
		EVMAccount account = accounts.get(address);
		if (account == null) {
			account = new EVMAccount(address);
			accounts.put(address, account);
		}
		return account;
	}

	public boolean accountExists(Address address) {
		EVMAccount account = accounts.get(address);
		return account != null && (account.hasCode() || account.getNonce() != 0
				|| account.getBalance().signum() != 0 || !getStorage(address).isEmpty());
	}

	/** Replaces (or inserts) the account, keeping its address. */
	public void setAccount(EVMAccount account) {
		accounts.put(account.getAddress(), account);
	}

	public void removeAccount(Address address) {
		accounts.remove(address);
		storage.remove(address);
	}

	public EVMStorage getStorage(Address address) {
		return storage.computeIfAbsent(address, k -> new EVMStorage());
	}

	public byte[] getCode(Address address) {
		EVMAccount account = accounts.get(address);
		return account == null ? new byte[0] : account.getCode();
	}

	public void setCode(Address address, byte[] code) {
		getOrCreateAccount(address).setCode(code);
	}

	public BigInteger getBalance(Address address) {
		EVMAccount account = accounts.get(address);
		return account == null ? BigInteger.ZERO : account.getBalance();
	}

	public void addBalance(Address address, BigInteger amount) {
		if (amount.signum() == 0) {
			return;
		}
		getOrCreateAccount(address).setBalance(getBalance(address).add(amount));
	}

	/**
	 * Transfers {@code amount} from {@code from} to {@code to}. Returns false if
	 * {@code from} has insufficient balance (no state is changed).
	 */
	public boolean transfer(Address from, Address to, BigInteger amount) {
		if (amount.signum() < 0) {
			throw new IllegalArgumentException("negative transfer");
		}
		if (amount.signum() == 0) {
			return true;
		}
		EVMAccount fromAccount = getOrCreateAccount(from);
		if (fromAccount.getBalance().compareTo(amount) < 0) {
			return false;
		}
		fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
		addBalance(to, amount);
		return true;
	}

	/**
	 * Deducts {@code amount} from the account's EVM balance (no recipient, e.g.
	 * a UTXO-layer withdrawal). Returns false if the balance is insufficient.
	 */
	public boolean subtractBalance(Address address, BigInteger amount) {
		if (amount.signum() < 0) {
			throw new IllegalArgumentException("negative amount");
		}
		if (amount.signum() == 0) {
			return true;
		}
		EVMAccount account = getOrCreateAccount(address);
		if (account.getBalance().compareTo(amount) < 0) {
			return false;
		}
		account.setBalance(account.getBalance().subtract(amount));
		return true;
	}

	/** Sorted set of all accounts (deterministic iteration order). */
	public SortedSet<Address> addresses() {
		return new TreeSet<>(accounts.keySet());
	}

	public int accountCount() {
		return accounts.size();
	}

	public WorldState copy() {
		WorldState copy = new WorldState();
		for (Map.Entry<Address, EVMAccount> e : accounts.entrySet()) {
			copy.accounts.put(e.getKey(), e.getValue().copy());
		}
		for (Map.Entry<Address, EVMStorage> e : storage.entrySet()) {
			copy.storage.put(e.getKey(), e.getValue().copy());
		}
		return copy;
	}

	/** Replaces this state's contents with a deep copy of {@code other}. */
	public void replaceFrom(WorldState other) {
		accounts.clear();
		storage.clear();
		for (Map.Entry<Address, EVMAccount> e : other.accounts.entrySet()) {
			accounts.put(e.getKey(), e.getValue().copy());
		}
		for (Map.Entry<Address, EVMStorage> e : other.storage.entrySet()) {
			storage.put(e.getKey(), e.getValue().copy());
		}
	}
}
