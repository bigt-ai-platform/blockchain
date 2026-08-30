import java.math.BigInteger;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.layer0.params.Layer0TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

/**
 * PegInTool — submit ONE real L0->L1 peg-in (layer vault bootstrap).
 *
 * <p>Locks a confirmed bc UTXO of the funding wallet to the vault address and
 * declares the L1 beneficiary + destination chain in the signed transaction,
 * so the L1 chain (PegInWatcherService) mints the wrapped token 1:1. This is
 * the design-consistent way to fund an L1 chain that must NOT mint bc at
 * genesis (see layers.md §5.2, §8).
 *
 * <p>Usage:
 * <pre>
 *   PegInTool &lt;fundingSeedHex&gt; &lt;beneficiaryPubKeyHex&gt; &lt;chainId&gt;
 *             &lt;vaultPubKeyHex&gt; &lt;l0Url&gt;
 * </pre>
 *
 * <p>Mirrors {@code BridgeServiceTest.createRealVault} and
 * {@code TransferOnce}: builds a signed 1-input/1-output transaction that pays
 * the vault script 1:1, sets {@code toAddressInSubtangle} to the beneficiary
 * and embeds {@code PegInInfo{chainId}} in the tx data, then POSTs the raw
 * serialized tx to L0 {@code /processPegIn}.
 *
 * <p>Prints FUNDING_ADDR, BENEFICIARY_ADDR, VAULT_ADDR, LOCKED_AMOUNT and the
 * L0 response, so the harness can derive the addresses for later polling.
 */
public class PegInTool {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: PegInTool <fundingSeedHex> <beneficiaryPubKeyHex> <chainId>"
                    + " <vaultPubKeyHex> <l0Url>");
            System.exit(1);
        }
        String fundingSeedHex = args[0].trim();
        String beneficiaryPubKeyHex = args[1].trim();
        String chainId = args[2].trim();
        String vaultPubKeyHex = args[3].trim();
        String l0Url = args[4].endsWith("/") ? args[4] : args[4] + "/";

        // The test net (server.net=Test) encodes addresses with TestParams
        // headers; Layer0TestParams matches the L0 server exactly.
        NetworkParameters params = new Layer0TestParams();

        PQKey fundingKey = PQKey.fromPrivateKeyHex(fundingSeedHex);
        Address fundingAddr = Address.fromHash160(params, fundingKey.getPubKeyHash());
        Address beneficiaryAddr = Address.fromHash160(params,
                Utils.sha256hash160(Utils.HEX.decode(beneficiaryPubKeyHex)));
        PQKey vaultKey = PQKey.fromPublicOnly(Utils.HEX.decode(vaultPubKeyHex));
        Address vaultAddr = Address.fromHash160(params,
                Utils.sha256hash160(vaultKey.getPubKey()));

        System.out.println("FUNDING_ADDR=" + fundingAddr.toBase58());
        System.out.println("BENEFICIARY_ADDR=" + beneficiaryAddr.toBase58());
        System.out.println("VAULT_ADDR=" + vaultAddr.toBase58());

        // Pick a confirmed, unspent bc UTXO of the funding wallet.
        UTXO source = null;
        ObjectMapper mapper = Json.jsonmapper();
        for (int attempt = 0; attempt < 10 && source == null; attempt++) {
            byte[] resp = OkHttp3Util.postString(l0Url + "getBalances",
                    mapper.writeValueAsString(List.of(Utils.HEX.encode(fundingKey.getPubKeyHash()))));
            GetBalancesResponse bal = mapper.readValue(resp, GetBalancesResponse.class);
            for (UTXO u : bal.getOutputs()) {
                if (u == null || u.getValue() == null || u.isSpent() || !u.isConfirmed()) {
                    continue;
                }
                if (java.util.Arrays.equals(u.getValue().getTokenid(),
                        NetworkParameters.BIGTANGLE_TOKENID)) {
                    source = u;
                    break;
                }
            }
            if (source == null) {
                System.err.println("no confirmed spendable bc UTXO yet for " + fundingAddr
                        + " (attempt " + (attempt + 1) + "), retrying");
                Thread.sleep(2000);
            }
        }
        if (source == null) {
            System.err.println("NO_SPENDABLE_BC: funding wallet " + fundingAddr
                    + " has no confirmed unspent bc UTXO on " + l0Url);
            System.exit(2);
        }
        System.out.println("LOCKED_AMOUNT=" + source.getValue().getValue());

        // Build the signed peg-in tx (mirrors BridgeServiceTest.createRealVault).
        Transaction tx = new Transaction(params);
        tx.setVersion(PQConstants.TX_PQ_VERSION);
        tx.setToAddressInSubtangle(beneficiaryAddr.getHash160());
        tx.setDataClassName("PegInInfo");
        tx.setData(mapper.writeValueAsBytes(java.util.Map.of("chainId", chainId)));
        FreeStandingTransactionOutput co = new FreeStandingTransactionOutput(params, source);
        tx.addInput(source.getBlockHash(), co);
        tx.getInputs().get(0).getOutpoint().connectedOutput = co;
        tx.addOutput(source.getValue(), vaultAddr);
        Sha256Hash sighash = tx.hashForSignature(0, source.getScript().getProgram(),
                Transaction.SigHash.ALL, false);
        tx.getInputs().get(0).setScriptSig(
                ScriptBuilder.createInputScriptForPQ(fundingKey.sign(sighash), fundingKey));

        byte[] resp = OkHttp3Util.post(l0Url + "processPegIn", tx.bitcoinSerialize());
        String body = new String(resp, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("PEGIN_RESPONSE=" + (body.length() > 300 ? body.substring(0, 300) : body));
        if (body.contains("\"errorcode\":0") || body.contains("\"errorcode\" : 0")) {
            System.out.println("PEGIN_OK=true");
        } else {
            System.out.println("PEGIN_OK=false");
        }
        System.out.println("PEGIN_TX=" + tx.getHashAsString());
    }
}
