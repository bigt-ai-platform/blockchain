/*******************************************************************************
 *  Copyright   2018  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.tools;

import java.security.SecureRandom;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;

/**
 * Command-line helper to produce validator credentials for a PoS node.
 *
 * <pre>
 *   ValidatorKeyTool generate              -> random 32-byte ML-DSA seed
 *   ValidatorKeyTool pubkey &lt;seedHex&gt;      -> derive pubkey/address from a seed
 * </pre>
 *
 * Run from a packaged exec jar, e.g.
 * {@code java -cp layer0-mcmc-0.6.0-exec.jar net.bigtangle.tools.ValidatorKeyTool generate}.
 */
public class ValidatorKeyTool {

    public static void main(String[] args) throws Exception {
        NetworkParameters params = MainNetParams.get();
        if (args.length >= 1 && "generate".equals(args[0])) {
            byte[] seed = new byte[32];
            new SecureRandom().nextBytes(seed);
            print(params, seed);
        } else if (args.length >= 2 && "pubkey".equals(args[0])) {
            print(params, Utils.HEX.decode(args[1].trim()));
        } else {
            System.err.println("Usage: ValidatorKeyTool generate | pubkey <seedHex (64 or 128 hex)>");
            System.exit(1);
        }
    }

    private static void print(NetworkParameters params, byte[] seed) {
        PQKey key = PQKey.fromPrivateKeyHex(Utils.HEX.encode(seed));
        String address = Address.fromHash160(params, key.getPubKeyHash()).toBase58();
        System.out.println("POS_VALIDATOR_KEY=" + Utils.HEX.encode(seed));
        System.out.println("VALIDATOR_PUBKEY=" + Utils.HEX.encode(key.getPubKey()));
        System.out.println("PUBKEY_HASH=" + Utils.HEX.encode(key.getPubKeyHash()));
        System.out.println("ADDRESS=" + address);
    }
}
