import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

/**
 * Real chain-TPS load: signed TRANSFER txs over HTTP per node key.
 * args: seedsFile urlPrefix durationSec amountSat
 * seedsFile: one ML-DSA seed hex per line; worker i -> node i (port 8281+i).
 */
public class TransferLoadTool {
    public static void main(String[] args) throws Exception {
        List<String> seeds = Files.readAllLines(Paths.get(args[0]));
        String urlPrefix = args[1];
        long durationSec = Long.parseLong(args[2]);
        long amountSat = Long.parseLong(args[3]);
        NetworkParameters params = MainNetParams.get();

        int n = seeds.size();
        Wallet[] wallets = new Wallet[n];
        String[] urls = new String[n];
        for (int i = 0; i < n; i++) {
            wallets[i] = Wallet.fromKeys(params,
                    PQKey.fromMLDSA(Utils.HEX.decode(seeds.get(i).trim())),
                    "/tmp/opencode/walletctx" + i);
            urls[i] = urlPrefix + (8281 + i) + "/";
            wallets[i].setServerURL(urls[i]);
        }
        String[] recipients = new String[8];
        for (int r = 0; r < recipients.length; r++) {
            byte[] rs = new byte[32];
            Arrays.fill(rs, (byte) (0x50 + r));
            recipients[r] = Address.fromHash160(params, PQKey.fromMLDSA(rs).getPubKeyHash()).toBase58();
        }
        Files.write(Paths.get("/tmp/opencode/recipients.txt"), Arrays.asList(recipients));

        AtomicLong ok = new AtomicLong(), err = new AtomicLong();
        long deadline = System.currentTimeMillis() + durationSec * 1000;
        Thread[] ts = new Thread[n];
        for (int w = 0; w < n; w++) {
            final int idx = w;
            ts[w] = new Thread(() -> {
                Wallet wallet = wallets[idx];
                int rot = 0;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        List<net.bigtangle.wallet.FreeStandingTransactionOutput> cands =
                                wallet.calculateAllSpendCandidates(null, false);
                        if (cands.isEmpty()) {
                            if (err.incrementAndGet() <= 2) {
                                System.out.println("w" + idx + ": no candidates; wallet addr="
                                    + Address.fromHash160(params,
                                        wallets[idx].walletKeys(null).get(0).getPubKeyHash()).toBase58()
                                   );
                            }
                            Thread.sleep(500);
                            continue;
                        }
                        HashMap<String, BigInteger> give = new HashMap<>();
                        give.put(recipients[(idx + rot++) % recipients.length],
                                BigInteger.valueOf(amountSat));
                        Transaction tx = wallet.payToListTransaction(null, give,
                                NetworkParameters.BIGTANGLE_TOKENID, "tps", cands);
                        if (tx == null) {
                            err.incrementAndGet();
                            continue;
                        }
                        OkHttp3Util.post(urls[idx] + "submitTransaction", tx.bitcoinSerialize());
                        ok.incrementAndGet();
                    } catch (Exception e) {
                        long en = err.incrementAndGet();
                        if (en <= 3) { System.out.println("ERR: " + e); }
                        try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
                    }
                }
            });
            ts[w].start();
        }
        for (Thread th : ts) th.join();
        System.out.printf("DONE submitted=%d err=%d avg=%.1f tx/s%n",
                ok.get(), err.get(),
                ok.get() / Math.max(1.0, durationSec));
    }
}
