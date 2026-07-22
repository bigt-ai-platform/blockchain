/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static com.google.common.base.Preconditions.checkState;
import static net.bigtangle.core.Coin.COIN;
import static net.bigtangle.core.Coin.valueOf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.ProtocolException;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.ScriptBuilder;
 

public class FakeTxBuilder {

   

    /** Create a fake transaction, without change. */
    public static Transaction createFakeTx(final NetworkParameters params) {
        return createFakeTxWithoutChangeAddress(params, Coin.COIN, PQKey.createNew().toAddress(params));
    }

    /** Create a fake transaction, without change. */
    public static Transaction createFakeTxWithoutChange(final NetworkParameters params,
            final TransactionOutput output) {
        Transaction prevTx = FakeTxBuilder.createFakeTx(params, Coin.COIN, PQKey.createNew().toAddress(params));
        Transaction tx = new Transaction(params);
        tx.addOutput(output);
        tx.addInput(UtilGeneseBlock.createGenesis(params) .getHash(), prevTx.getOutput(0));
        return tx;
    }

    /** Create a fake coinbase transaction. */
    public static Transaction createFakeCoinbaseTx(final NetworkParameters params) {
        TransactionOutPoint outpoint =   TransactionOutPoint.fromTransactionOutPoint4(params, -1, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH);
        TransactionInput input =   TransactionInput.fromOutpoint4(params, null, new byte[0], outpoint);
        Transaction tx = new Transaction(params);
        tx.addInput(input);
        TransactionOutput outputToMe =   TransactionOutput.fromAddress(params, tx, Coin.COIN.multiply(50),
                PQKey.createNew().toAddress(params));
        tx.addOutput(outputToMe);

        checkState(tx.isCoinBase());
        return tx;
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two
     * outputs, one to us, one to somewhere else to simulate change. There is
     * one random input.
     */
    public static Transaction createFakeTxWithChangeAddress(NetworkParameters params, Coin value, Address to,
            Address changeOutput) {
        Transaction t = new Transaction(params);
        TransactionOutput outputToMe =   TransactionOutput.fromAddress(params, t, value, to);
        t.addOutput(outputToMe);
        TransactionOutput change =   TransactionOutput.fromAddress(params, t,
                valueOf(Coin.COIN.getValue().longValue() * 1 + 11, NetworkParameters.BIGTANGLE_TOKENID), changeOutput);
        t.addOutput(change);
        // Make a previous tx simply to send us sufficient coins. This prev tx
        // is not really valid but it doesn't
        // matter for our purposes.
        Transaction prevTx = new Transaction(params);
        TransactionOutput prevOut =   TransactionOutput.fromAddress(params, prevTx, value, to);
        prevTx.addOutput(prevOut);
        // Connect it.
        t.addInput(UtilGeneseBlock.createGenesis(params) .getHash(), prevOut).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));
        // Fake signature.
        // Serialize/deserialize to ensure internal state is stripped, as if it
        // had been read from the wire.
        return roundTripTransaction(params, t);
    }

    /**
     * Create a fake TX for unit tests, for use with unit tests that need
     * greater control. One outputs, 2 random inputs, split randomly to create
     * randomness.
     */
    public static Transaction createFakeTxWithoutChangeAddress(NetworkParameters params, Coin value, Address to) {
        Transaction t = new Transaction(params);
        TransactionOutput outputToMe =   TransactionOutput.fromAddress(params, t, value, to);
        t.addOutput(outputToMe);

        // Make a random split in the output value so we get a distinct hash
        // when we call this multiple times with same args
        long split = new Random().nextLong();
        if (split < 0) {
            split *= -1;
        }
        if (split == 0) {
            split = 15;
        }
        while (split > value.getValue().longValue()) {
            split /= 2;
        }

        // Make a previous tx simply to send us sufficient coins. This prev tx
        // is not really valid but it doesn't
        // matter for our purposes.
        Transaction prevTx1 = new Transaction(params);
        TransactionOutput prevOut1 =   TransactionOutput.fromAddress(params, prevTx1,
                Coin.valueOf(split, NetworkParameters.BIGTANGLE_TOKENID), to);
        prevTx1.addOutput(prevOut1);
        // Connect it.
        t.addInput(UtilGeneseBlock.createGenesis(params) .getHash(), prevOut1).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));
        // Fake signature.

        // Do it again
        Transaction prevTx2 = new Transaction(params);
        TransactionOutput prevOut2 =   TransactionOutput.fromAddress(params, prevTx2,
                Coin.valueOf(value.getValue().longValue() - split, NetworkParameters.BIGTANGLE_TOKENID), to);
        prevTx2.addOutput(prevOut2);
        t.addInput(UtilGeneseBlock.createGenesis(params) .getHash(), prevOut2).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));

        // Serialize/deserialize to ensure internal state is stripped, as if it
        // had been read from the wire.
        return roundTripTransaction(params, t);
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two
     * outputs, one to us, one to somewhere else to simulate change. There is
     * one random input.
     */
    public static Transaction createFakeTx(NetworkParameters params, Coin value, Address to) {
        return createFakeTxWithChangeAddress(params, value, to, PQKey.createNew().toAddress(params));
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two
     * outputs, one to us, one to somewhere else to simulate change. There is
     * one random input.
     */
    public static Transaction createFakeTx(NetworkParameters params, Coin value, PQKey to) {
        Transaction t = new Transaction(params);
        TransactionOutput outputToMe =   TransactionOutput.fromCoinKey(params, t, value, to);
        t.addOutput(outputToMe);
        TransactionOutput change =   TransactionOutput.fromCoinKey(params, t,
                valueOf(Coin.COIN.getValue().longValue() * 1 + 11, NetworkParameters.BIGTANGLE_TOKENID), PQKey.createNew());
        t.addOutput(change);
        // Make a previous tx simply to send us sufficient coins. This prev tx
        // is not really valid but it doesn't
        // matter for our purposes.
        Transaction prevTx = new Transaction(params);
        TransactionOutput prevOut =   TransactionOutput.fromCoinKey(params, prevTx, value, to);
        prevTx.addOutput(prevOut);
        // Connect it.
        t.addInput(UtilGeneseBlock.createGenesis(params) .getHash(), prevOut);
        // Serialize/deserialize to ensure internal state is stripped, as if it
        // had been read from the wire.
        return roundTripTransaction(params, t);
    }

    /**
     * Transaction[0] is a feeder transaction, supplying BTA to Transaction[1]
     */
    public static Transaction[] createFakeTx(NetworkParameters params, Coin value, Address to, Address from) {
        // Create fake TXes of sufficient realism to exercise the unit tests.
        // This transaction send BTA from the
        // from address, to the to address with to one to somewhere else to
        // simulate change.
        Transaction t = new Transaction(params);
        TransactionOutput outputToMe =   TransactionOutput.fromAddress(params, t, value, to);
        t.addOutput(outputToMe);
        TransactionOutput change =   TransactionOutput.fromAddress(params, t,
                valueOf(Coin.COIN.getValue().longValue() * 1 + 11, NetworkParameters.BIGTANGLE_TOKENID), PQKey.createNew().toAddress(params));
        t.addOutput(change);
        // Make a feeder tx that sends to the from address specified. This
        // feeder tx is not really valid but it doesn't
        // matter for our purposes.
        Transaction feederTx = new Transaction(params);
        TransactionOutput feederOut =   TransactionOutput.fromAddress(params, feederTx, value, from);
        feederTx.addOutput(feederOut);

        // make a previous tx that sends from the feeder to the from address
        Transaction prevTx = new Transaction(params);
        TransactionOutput prevOut =   TransactionOutput.fromAddress(params, prevTx, value, to);
        prevTx.addOutput(prevOut);

        // Connect up the txes
        prevTx.addInput(UtilGeneseBlock.createGenesis(params) .getHash(), feederOut);
        t.addInput(UtilGeneseBlock.createGenesis(params) .getHash(), prevOut);

        // roundtrip the tx so that they are just like they would be from the
        // wire
        return new Transaction[] { roundTripTransaction(params, prevTx), roundTripTransaction(params, t) };
    }

    /**
     * Roundtrip a transaction so that it appears as if it has just come from
     * the wire
     */
    public static Transaction roundTripTransaction(NetworkParameters params, Transaction tx) {
        try {
            MessageSerializer bs = params.getDefaultSerializer();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bs.serialize(tx, bos);
            return (Transaction) bs.deserialize(ByteBuffer.wrap(bos.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException(e); // Should not happen.
        }
    }

    public static class DoubleSpends {
        public Transaction t1, t2, prevTx;
    }

    /**
     * Creates two transactions that spend the same (fake) output. t1 spends to
     * "to". t2 spends somewhere else. The fake output goes to the same address
     * as t2.
     */
    public static DoubleSpends createFakeDoubleSpendTxns(NetworkParameters params, Address to) {
        DoubleSpends doubleSpends = new DoubleSpends();
        Coin value = COIN;
        Address someBadGuy = PQKey.createNew();

        doubleSpends.prevTx = new Transaction(params);
        TransactionOutput prevOut =   TransactionOutput.fromAddress(params, doubleSpends.prevTx, value, someBadGuy);
        doubleSpends.prevTx.addOutput(prevOut);

        doubleSpends.t1 = new Transaction(params);
        TransactionOutput o1 =   TransactionOutput.fromAddress(params, doubleSpends.t1, value, to);
        doubleSpends.t1.addOutput(o1);
        doubleSpends.t1.addInput(UtilGeneseBlock.createGenesis(params) .getHash(), prevOut);

        doubleSpends.t2 = new Transaction(params);
        doubleSpends.t2.addInput(UtilGeneseBlock.createGenesis(params) .getHash(), prevOut);
        TransactionOutput o2 =   TransactionOutput.fromAddress(params, doubleSpends.t2, value, someBadGuy);
        doubleSpends.t2.addOutput(o2);

        try {
            doubleSpends.t1 = params.getDefaultSerializer().makeTransaction(doubleSpends.t1.bitcoinSerialize());
            doubleSpends.t2 = params.getDefaultSerializer().makeTransaction(doubleSpends.t2.bitcoinSerialize());
        } catch (ProtocolException e) {
            throw new RuntimeException(e);
        }
        return doubleSpends;
    }

    public static class BlockPair {
    
        public Block block;
    }

    
 
 
    public static Block makeSolvedTestBlock(Block prev, Transaction... transactions) throws BlockStoreException {
       // Address to = PQKey.createNew();
        Block b = UtilsTest.createBlock(MainNetParams.get(), prev, prev);
        // Coinbase tx already exists.
        for (Transaction tx : transactions) {
            b.addTransaction(tx);
        }
        return b;
    }

    public static Block makeSolvedTestBlock(Block prev, Address to, Transaction... transactions)
            throws BlockStoreException {
        Block b = UtilsTest.createBlock(MainNetParams.get(),prev, prev);
        // Coinbase tx already exists.
        for (Transaction tx : transactions) {
            b.addTransaction(tx);
        }
        return b;
    }
}
