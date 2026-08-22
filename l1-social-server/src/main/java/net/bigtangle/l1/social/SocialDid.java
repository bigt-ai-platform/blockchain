package net.bigtangle.l1.social;

import java.util.Arrays;

import net.bigtangle.core.Address;

import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.Base58;

/**
 * Minimal did:key (ML-DSA-87 prefixed, aifeeds codepoint 0x300001) parsing for
 * server-side record validation. Mirrors @aifeeds/did pq.ts.
 */
public final class SocialDid {

    private static final int MULTICODEC_AIFEEDS_MLDSA87_PREFIXED = 0x300001;

    private SocialDid() {}

    public static byte[] pubFromDid(String did) {
        if (did == null || !did.startsWith("did:key:z"))
            throw new IllegalArgumentException("unsupported DID format");
        byte[] decoded = Base58.decode(did.substring(9));
        int value = 0, shift = 0, i = 0;
        int b;
        do {
            if (i >= decoded.length) throw new IllegalArgumentException("bad multicodec varint");
            b = decoded[i] & 0xff;
            value |= (b & 0x7f) << shift;
            shift += 7;
            i++;
        } while ((b & 0x80) != 0);
        if (value != MULTICODEC_AIFEEDS_MLDSA87_PREFIXED)
            throw new IllegalArgumentException("unsupported multicodec: 0x" + Integer.toHexString(value));
        return Arrays.copyOfRange(decoded, i, decoded.length);
    }

    /** Chain address (testnet P2PKH base58) of the did:key holder. */
    public static String addressFromDid(NetworkParameters params, String did) {
        byte[] prefixedPub = pubFromDid(did);
        return Address.fromHash160(params, Utils.sha256hash160(prefixedPub)).toBase58();
    }
}
