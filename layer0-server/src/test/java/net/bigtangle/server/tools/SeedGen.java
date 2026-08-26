import java.util.Arrays;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;

/**
 * Generates deterministic test wallet seeds + base58 addresses for load
 * testing. Seed r uses an ML-DSA seed filled with (byte)(0x60 + r), distinct
 * from TransferLoadTool's recipients (0x50+r) and node keys.
 * args: count -> one "seedhex address" per line.
 */
public class SeedGen {
    public static void main(String[] args) {
        int count = Integer.parseInt(args[0]);
        for (int r = 0; r < count; r++) {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) (0x60 + (r & 0x1f)));
            PQKey k = PQKey.fromMLDSA(seed);
            System.out.println(Utils.HEX.encode(seed) + " "
                    + Address.fromHash160(MainNetParams.get(), k.getPubKeyHash()).toBase58());
        }
    }
}
