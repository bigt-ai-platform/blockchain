import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * TransferOnce — submit ONE real signed transfer tx (testnodes.sh transfer
 * step). fundAddresses only mints a node-local UTXO ("test/bootstrap only"),
 * so the visibility check must exercise an actual propagating transaction.
 *
 * Usage: TransferOnce <seedHex> <toPubKeyHashHex> <amountSat> <apiUrl> [workDir]
 * Mirrors TransferLoadTool: Wallet candidates -> payToListTransaction ->
 * length-prefixed submitTransactions.
 */
public class TransferOnce {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: TransferOnce <seedHex> <toPubKeyHashHex> <amountSat> <apiUrl> [workDir]");
            System.exit(1);
        }
        NetworkParameters params = MainNetParams.get();
        PQKey key = PQKey.fromPrivateKeyHex(args[0].trim());
        String workDir = args.length > 4 ? args[4] : "/tmp/bigtangle-transfer-wallet";
        Wallet wallet = Wallet.fromKeys(params, key, workDir);
        String url = args[3].endsWith("/") ? args[3] : args[3] + "/";
        wallet.setServerURL(url);

        java.util.List<FreeStandingTransactionOutput> cands = wallet.calculateAllSpendCandidates(null, false);
        if (cands.isEmpty()) {
            System.err.println("NO_CANDIDATES: no spendable outputs for the wallet");
            System.exit(2);
        }
        Address to = Address.fromHash160(params, Utils.HEX.decode(args[1].trim()));
        HashMap<String, BigInteger> give = new HashMap<>();
        give.put(to.toBase58(), BigInteger.valueOf(Long.parseLong(args[2])));
        Transaction tx = wallet.payToListTransaction(null, give, NetworkParameters.BIGTANGLE_TOKENID,
                "testnodes-transfer", Collections.singletonList(cands.get(0)));
        if (tx == null) {
            System.err.println("TX_BUILD_FAILED");
            System.exit(3);
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        byte[] b = tx.bitcoinSerialize();
        dos.writeInt(b.length);
        dos.write(b);
        dos.close();
        OkHttp3Util.post(url + "submitTransactions", baos.toByteArray());
        System.out.println("TXID=" + tx.getHashAsString());
    }
}
