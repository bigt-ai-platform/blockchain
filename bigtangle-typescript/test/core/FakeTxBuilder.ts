import { Buffer } from 'buffer';
import { NetworkParameters } from '../../src/net/bigtangle/core/NetworkParameters';
import { Transaction } from '../../src/net/bigtangle/core/Transaction';
import { Coin } from '../../src/net/bigtangle/core/Coin';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { Address } from '../../src/net/bigtangle/core/Address';
import { TransactionOutput } from '../../src/net/bigtangle/core/TransactionOutput';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { TransactionOutPoint } from '../../src/net/bigtangle/core/TransactionOutPoint';
import { TransactionInput } from '../../src/net/bigtangle/core/TransactionInput';
import { ScriptBuilder } from '../../src/net/bigtangle/script/ScriptBuilder';
import { TransactionSignature } from '../../src/net/bigtangle/crypto/TransactionSignature';
import { Block } from '../../src/net/bigtangle/core/Block';
import { UtilsTest } from './UtilsTest';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { BigInteger } from '../../src/net/bigtangle/core/BigInteger';

export class FakeTxBuilder {
    /** Create a fake transaction, without change. */
    public static createFakeTx(params: NetworkParameters): Transaction {
        return FakeTxBuilder.createFakeTxWithoutChangeAddress(
            params,
            Coin.COIN,
            ECKey.fromPrivate(new BigInteger('1')).toAddress(params),
        );
    }

    /** Create a fake transaction, without change. */
    public static createFakeTxWithoutChange(
        params: NetworkParameters,
        output: TransactionOutput,
    ): Transaction {
        const prevTx = FakeTxBuilder.createFakeTx(
            params,
            Coin.COIN,
            ECKey.fromPrivate(new BigInteger('1')).toAddress(params),
        );
        const tx = new Transaction(params);
        tx.addOutput(output);
        tx.addInput(params.getGenesisBlock().getHash(), prevTx.getOutput(0));
        return tx;
    }

    /** Create a fake coinbase transaction. */
    public static createFakeCoinbaseTx(params: NetworkParameters): Transaction {
        const outpoint = new TransactionOutPoint(
            params,
            -1,
            Sha256Hash.ZERO_HASH,
            Sha256Hash.ZERO_HASH,
        );
        const input = new TransactionInput(params, null, Buffer.from([]), outpoint);
        const tx = new Transaction(params);
        tx.addInput(input);
        const outputToMe = new TransactionOutput(
            params,
            tx,
            Coin.COIN.multiply(50),
            ECKey.fromPrivate(new BigInteger('1')).toAddress(params),
        );
        tx.addOutput(outputToMe);

        if (!tx.isCoinBase()) {
            throw new Error('Created transaction is not a coinbase transaction');
        }
        return tx;
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two
     * outputs, one to us, one to somewhere else to simulate change. There is
     * one random input.
     */
    public static createFakeTxWithChangeAddress(
        params: NetworkParameters,
        value: Coin,
        to: Address,
        changeOutput: Address,
    ): Transaction {
        const t = new Transaction(params);
        const outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);
        const change = new TransactionOutput(
            params,
            t,
            Coin.valueOf(
                Coin.COIN.getValue() * 1 + 11,
                NetworkParameters.BIGTANGLE_TOKENID,
            ),
            changeOutput,
        );
        t.addOutput(change);
        // Make a previous tx simply to send us sufficient coins. This prev tx
        // is not really valid but it doesn't
        // matter for our purposes.
        const prevTx = new Transaction(params);
        const prevOut = new TransactionOutput(params, prevTx, value, to);
        prevTx.addOutput(prevOut);
        // Connect it.
        t.addInput(params.getGenesisBlock().getHash(), prevOut).setScriptSig(
            ScriptBuilder.createInputScript(TransactionSignature.dummy()),
        );
        // Fake signature.
        // Serialize/deserialize to ensure internal state is stripped, as if it
        // had been read from the wire.
        return FakeTxBuilder.roundTripTransaction(params, t);
    }

    /**
     * Create a fake TX for unit tests, for use with unit tests that need
     * greater control. One outputs, 2 random inputs, split randomly to create
     * randomness.
     */
    public static createFakeTxWithoutChangeAddress(
        params: NetworkParameters,
        value: Coin,
        to: Address,
    ): Transaction {
        const t = new Transaction(params);
        const outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);

        // Make a random split in the output value so we get a distinct hash
        // when we call this multiple times with same args
        let split = Math.floor(Math.random() * Number.MAX_SAFE_INTEGER);
        if (split < 0) {
            split *= -1;
        }
        if (split === 0) {
            split = 15;
        }
        while (split > value.getValue()) {
            split /= 2;
        }

        // Make a previous tx simply to send us sufficient coins. This prev tx
        // is not really valid but it doesn't
        // matter for our purposes.
        const prevTx1 = new Transaction(params);
        const prevOut1 = new TransactionOutput(
            params,
            prevTx1,
            Coin.valueOf(split, NetworkParameters.BIGTANGLE_TOKENID),
            to,
        );
        prevTx1.addOutput(prevOut1);
        // Connect it.
        t.addInput(params.getGenesisBlock().getHash(), prevOut1).setScriptSig(
            ScriptBuilder.createInputScript(TransactionSignature.dummy()),
        );
        // Fake signature.

        // Do it again
        const prevTx2 = new Transaction(params);
        const prevOut2 = new TransactionOutput(
            params,
            prevTx2,
            Coin.valueOf(
                value.getValue() - split,
                NetworkParameters.BIGTANGLE_TOKENID,
            ),
            to,
        );
        prevTx2.addOutput(prevOut2);
        t.addInput(params.getGenesisBlock().getHash(), prevOut2).setScriptSig(
            ScriptBuilder.createInputScript(TransactionSignature.dummy()),
        );

        // Serialize/deserialize to ensure internal state is stripped, as if it
        // had been read from the wire.
        return FakeTxBuilder.roundTripTransaction(params, t);
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two
     * outputs, one to us, one to somewhere else to simulate change. There is
     * one random input.
     */
    public static createFakeTx(
        params: NetworkParameters,
        value: Coin,
        to: Address,
    ): Transaction {
        return FakeTxBuilder.createFakeTxWithChangeAddress(
            params,
            value,
            to,
            ECKey.fromPrivate(new BigInteger('1')).toAddress(params),
        );
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two
     * outputs, one to us, one to somewhere else to simulate change. There is
     * one random input.
     */
    public static createFakeTxECKey(
        params: NetworkParameters,
        value: Coin,
        to: ECKey,
    ): Transaction {
        const t = new Transaction(params);
        const outputToMe = new TransactionOutput(params, t, value, to.toAddress(params));
        t.addOutput(outputToMe);
        const change = new TransactionOutput(
            params,
            t,
            Coin.valueOf(
                Coin.COIN.getValue() * 1 + 11,
                NetworkParameters.BIGTANGLE_TOKENID,
            ),
            new ECKey().toAddress(params),
        );
        t.addOutput(change);
        // Make a previous tx simply to send us sufficient coins. This prev tx
        // is not really valid but it doesn't
        // matter for our purposes.
        const prevTx = new Transaction(params);
        const prevOut = new TransactionOutput(params, prevTx, value, to.toAddress(params));
        prevTx.addOutput(prevOut);
        // Connect it.
        t.addInput(params.getGenesisBlock().getHash(), prevOut);
        // Serialize/deserialize to ensure internal state is stripped, as if it
        // had been read from the wire.
        return FakeTxBuilder.roundTripTransaction(params, t);
    }

    /**
     * Transaction[0] is a feeder transaction, supplying BTA to Transaction[1]
     */
    public static createFakeTxFrom(
        params: NetworkParameters,
        value: Coin,
        to: Address,
        from: Address,
    ): Transaction[] {
        // Create fake TXes of sufficient realism to exercise the unit tests.
        // This transaction send BTA from the
        // from address, to the to address with to one to somewhere else to
        // simulate change.
        const t = new Transaction(params);
        const outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);
        const change = new TransactionOutput(
            params,
            t,
            Coin.valueOf(
                Coin.COIN.getValue() * 1 + 11,
                NetworkParameters.BIGTANGLE_TOKENID,
            ),
            new ECKey().toAddress(params),
        );
        t.addOutput(change);
        // Make a feeder tx that sends to the from address specified. This
        // feeder tx is not really valid but it doesn't
        // matter for our purposes.
        const feederTx = new Transaction(params);
        const feederOut = new TransactionOutput(params, feederTx, value, from);
        feederTx.addOutput(feederOut);

        // make a previous tx that sends from the feeder to the from address
        const prevTx = new Transaction(params);
        const prevOut = new TransactionOutput(params, prevTx, value, to);
        prevTx.addOutput(prevOut);

        // Connect up the txes
        prevTx.addInput(params.getGenesisBlock().getHash(), feederOut);
        t.addInput(params.getGenesisBlock().getHash(), prevOut);

        // roundtrip the tx so that they are just like they would be from the
        // wire
        return [
            FakeTxBuilder.roundTripTransaction(params, prevTx),
            FakeTxBuilder.roundTripTransaction(params, t),
        ];
    }

    /**
     * Roundtrip a transaction so that it appears as if it has just come from
     * the wire
     */
    public static roundTripTransaction(
        params: NetworkParameters,
        tx: Transaction,
    ): Transaction {
        try {
            const bs = params.getDefaultSerializer();
            const bos = tx.bitcoinSerialize();
            return bs.deserialize(bos) as Transaction;
        } catch (e) {
            throw new Error(e); // Should not happen.
        }
    }

    public static createFakeDoubleSpendTxns(
        params: NetworkParameters,
        to: Address,
    ): DoubleSpends {
        const doubleSpends = new DoubleSpends();
        const value = Coin.COIN;
        const someBadGuy = new ECKey().toAddress(params);

        doubleSpends.prevTx = new Transaction(params);
        const prevOut = new TransactionOutput(
            params,
            doubleSpends.prevTx,
            value,
            someBadGuy,
        );
        doubleSpends.prevTx.addOutput(prevOut);

        doubleSpends.t1 = new Transaction(params);
        const o1 = new TransactionOutput(params, doubleSpends.t1, value, to);
        doubleSpends.t1.addOutput(o1);
        doubleSpends.t1.addInput(params.getGenesisBlock().getHash(), prevOut);

        doubleSpends.t2 = new Transaction(params);
        doubleSpends.t2.addInput(params.getGenesisBlock().getHash(), prevOut);
        const o2 = new TransactionOutput(
            params,
            doubleSpends.t2,
            value,
            someBadGuy,
        );
        doubleSpends.t2.addOutput(o2);

        try {
            doubleSpends.t1 = params
                .getDefaultSerializer()
                .makeTransaction(doubleSpends.t1.bitcoinSerialize());
            doubleSpends.t2 = params
                .getDefaultSerializer()
                .makeTransaction(doubleSpends.t2.bitcoinSerialize());
        } catch (e) {
            throw new Error(e);
        }
        return doubleSpends;
    }

    public static makeSolvedTestBlock(
        prev: Block,
        ...transactions: Transaction[]
    ): Block {
        const b = UtilsTest.createBlock(MainNetParams.get(), prev, prev);
        // Coinbase tx already exists.
        for (const tx of transactions) {
            b.addTransaction(tx);
        }
        b.solve();
        return b;
    }

    public static makeSolvedTestBlockWithAddress(
        prev: Block,
        to: Address,
        ...transactions: Transaction[]
    ): Block {
        const b = UtilsTest.createBlock(MainNetParams.get(), prev, prev);
        // Coinbase tx already exists.
        for (const tx of transactions) {
            b.addTransaction(tx);
        }
        b.solve();
        return b;
    }
}

export class DoubleSpends {
    public t1: Transaction;
    public t2: Transaction;
    public prevTx: Transaction;
}

export class BlockPair {
    public block: Block;
}
