import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.server.service.StakeService;

/**
 * Signs a PoS voluntary-exit request for requestValidatorExit:
 *   java SignExit.java <POS_VALIDATOR_KEY hex> <nonce=confirmed chainLength>
 * Prints PUBKEY=<hex> and SIGNATURE=<SignatureBundle hex> (self-verified).
 */
public class SignExit {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: SignExit.java <validator-key-hex> <nonce>");
            System.exit(2);
        }
        PQKey key = PQKey.fromPrivateKeyHex(args[0].trim());
        long nonce = Long.parseLong(args[1].trim());
        byte[] pub = key.getPubKey();
        Sha256Hash msg = Sha256Hash.of(StakeService.buildExitMessage(pub, nonce));
        byte[] sig = key.sign(msg).serialize();
        if (!PQScriptUtils.verifyPQ(key.getPublicKeyBytes(), sig, msg)) {
            System.err.println("self-verify FAILED");
            System.exit(1);
        }
        System.out.println("PUBKEY=" + Utils.HEX.encode(pub));
        System.out.println("SIGNATURE=" + Utils.HEX.encode(sig));
    }
}
