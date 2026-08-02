package net.bigtangle.evm;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic mapping between the UTXO layer's base58 address and an EVM
 * address: {@code evmAddress = last20(keccak256(base58AddressBytes))}. The base58
 * address already commits to the ML-DSA public key, so every node derives the
 * same EVM address for a given wallet.
 */
public final class EVMAddressUtil {

	private EVMAddressUtil() {
	}

	public static Address evmAddressFromBase58(String base58Address) {
		if (base58Address == null) {
			return Address.ZERO;
		}
		return Address.fromLast20Bytes(Keccak.hash(base58Address.getBytes(StandardCharsets.UTF_8)));
	}
}
