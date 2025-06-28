/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.server.performance;

import java.io.IOException;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.EnumSet;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.bigtangle.script.ScriptOpCodes.getOpCodeName;

import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.exception.ScriptException;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.script.ScriptChunk;
import net.bigtangle.signers.LocalTransactionSigner;
import net.bigtangle.signers.TransactionSigner.ProposedTransaction;
import net.bigtangle.wallet.KeyBag;
import net.bigtangle.wallet.RedeemData;

/**
 * Test case generator for transactions with low-S and high-S signatures, to
 * test the LOW_S script validation flag.
 *
 * @author Ross Nicoll
 */
public class GenerateLowSTests {
    public static final BigInteger HIGH_S_DIFFERENCE = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    public static void main(final String[] argv) throws NoSuchAlgorithmException, IOException {
        final NetworkParameters params = new MainNetParams();
        final LocalTransactionSigner signer = new LocalTransactionSigner();
        final SecureRandom secureRandom = SecureRandom.getInstanceStrong();
        final ECKey key = new ECKey(secureRandom);
        final KeyBag bag = new KeyBag() {
            @Override
            public ECKey findKeyFromPubHash(byte[] pubkeyHash) {
                return key;
            }

            @Override
            public ECKey findKeyFromPubKey(byte[] pubkey) {
                return key;
            }

            @Override
            public RedeemData findRedeemDataFromScriptHash(byte[] scriptHash) {
                return null;
            }

        };

        // Generate a fictional output transaction we take values from, and
        // an input transaction for the test case

        final Transaction outputTransaction = new Transaction(params);
        final Transaction inputTransaction = new Transaction(params);
        final TransactionOutput output = new TransactionOutput(params, inputTransaction, Coin.ZERO, key.toAddress(params));

        inputTransaction.addOutput(output);
        outputTransaction.addInput(Utils.createGenesis(params).getHash(), output);
        outputTransaction.addOutput(Coin.ZERO, new ECKey(secureRandom).toAddress(params));

        addOutputs(outputTransaction, bag);

        // Sign the transaction
        final ProposedTransaction proposedTransaction = new ProposedTransaction(outputTransaction);
        signer.signInputs(proposedTransaction, bag);
        final TransactionInput input = proposedTransaction.partialTx.getInput(0);

        input.verify(output);
        input.getScriptSig().correctlySpends(outputTransaction, 0, output.getScriptPubKey(),
            EnumSet.of(Script.VerifyFlag.DERSIG, Script.VerifyFlag.P2SH));

        final Script scriptSig = input.getScriptSig();
        final TransactionSignature signature = TransactionSignature.decodeFromBitcoin(scriptSig.getChunks().get(0).data, true, false);

        // First output a conventional low-S transaction with the LOW_S flag, for the tx_valid.json set
        System.out.println("[\"A transaction with a low-S signature.\"],");
        System.out.println("[[[\""
            + inputTransaction.getHashAsString() + "\", "
            + output.getIndex() + ", \""
            + scriptToString(output.getScriptPubKey()) + "\"]],\n"
            + "\"" + Utils.HEX.encode(proposedTransaction.partialTx.unsafeBitcoinSerialize()) + "\", \""
            + Script.VerifyFlag.P2SH.name() + "," + Script.VerifyFlag.LOW_S.name() + "\"],");

        final BigInteger highS = HIGH_S_DIFFERENCE.subtract(signature.s);
        final TransactionSignature highSig = new TransactionSignature(signature.r, highS);
        input.setScriptSig(new ScriptBuilder().data(highSig.encodeToBitcoin()).data(scriptSig.getChunks().get(1).data).build());
        input.getScriptSig().correctlySpends(outputTransaction, 0, output.getScriptPubKey(),
            EnumSet.of(Script.VerifyFlag.P2SH));

        // A high-S transaction without the LOW_S flag, for the tx_valid.json set
        System.out.println("[\"A transaction with a high-S signature.\"],");
        System.out.println("[[[\""
            + inputTransaction.getHashAsString() + "\", "
            + output.getIndex() + ", \""
            + scriptToString(output.getScriptPubKey()) + "\"]],\n"
            + "\"" + Utils.HEX.encode(proposedTransaction.partialTx.unsafeBitcoinSerialize()) + "\", \""
            + Script.VerifyFlag.P2SH.name() + "\"],");

        // Lastly a conventional high-S transaction with the LOW_S flag, for the tx_invalid.json set
        System.out.println("[\"A transaction with a high-S signature.\"],");
        System.out.println("[[[\""
            + inputTransaction.getHashAsString() + "\", "
            + output.getIndex() + ", \""
            + scriptToString(output.getScriptPubKey()) + "\"]],\n"
            + "\"" + Utils.HEX.encode(proposedTransaction.partialTx.unsafeBitcoinSerialize()) + "\", \""
            + Script.VerifyFlag.P2SH.name() + "," + Script.VerifyFlag.LOW_S.name() + "\"],");
    }

    private static void addOutputs(final Transaction outputTransaction, final KeyBag bag) throws ScriptException {
        int numInputs = outputTransaction.getInputs().size();
        for (int i = 0; i < numInputs; i++) {
            TransactionInput txIn = outputTransaction.getInput(i);
            Script scriptPubKey = txIn.getConnectedOutput().getScriptPubKey();
            RedeemData redeemData = txIn.getConnectedRedeemData(bag);
            checkNotNull(redeemData, "Transaction exists in wallet that we cannot redeem: %s", txIn.getOutpoint().getTxHash());
            txIn.setScriptSig(scriptPubKey.createEmptyInputScript(redeemData.keys.get(0), redeemData.redeemScript));
        }
    }

    /**
     * Convert a script to a string format that suits the style expected in
     * tx_valid.json and tx_invalid.json.
     */
    private static String scriptToString(Script scriptPubKey) {
        final StringBuilder buf = new StringBuilder();
        for (ScriptChunk chunk: scriptPubKey.getChunks()) {
            if (buf.length() > 0) {
                buf.append(" ");
            }
            if (chunk.isOpCode()) {
                buf.append(getOpCodeName(chunk.opcode));
            } else if (chunk.data != null) {
                // Data chunk
                buf.append("0x")
                    .append(Integer.toString(chunk.opcode, 16)).append(" 0x")
                    .append(Utils.HEX.encode(chunk.data));
            } else {
                buf.append(chunk.toString());
            }
        }
        return buf.toString();
    }
}
