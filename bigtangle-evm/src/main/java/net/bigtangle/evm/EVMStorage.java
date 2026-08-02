package net.bigtangle.evm;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.bigtangle.core.Sha256Hash;

/**
 * Contract storage: a sorted map of 32-byte keys to 32-byte values. Absent keys
 * read as {@link Word#ZERO}. Sorted iteration makes the derived storage root
 * deterministic.
 */
public final class EVMStorage {

	private final TreeMap<Word, Word> slots = new TreeMap<>();

	public Word get(Word key) {
		Word value = slots.get(key);
		return value == null ? Word.ZERO : value;
	}

	public void put(Word key, Word value) {
		if (value.isZero()) {
			slots.remove(key);
		} else {
			slots.put(key, value);
		}
	}

	public void remove(Word key) {
		slots.remove(key);
	}

	public boolean isEmpty() {
		return slots.isEmpty();
	}

	public int size() {
		return slots.size();
	}

	public Set<Map.Entry<Word, Word>> entries() {
		return slots.entrySet();
	}

	/**
	 * Deterministic root over this storage: hash of the concatenation of
	 * {@code sha256(key || value)} leaves in sorted-key order.
	 */
	public Sha256Hash root() {
		if (slots.isEmpty()) {
			return Sha256Hash.ZERO_HASH;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Map.Entry<Word, Word> e : slots.entrySet()) {
			byte[] key = e.getKey().toBytes();
			byte[] value = e.getValue().toBytes();
			out.writeBytes(Sha256Hash.hash(concat(key, value)));
		}
		return Sha256Hash.of(out.toByteArray());
	}

	public EVMStorage copy() {
		EVMStorage copy = new EVMStorage();
		copy.slots.putAll(slots);
		return copy;
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = new byte[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
