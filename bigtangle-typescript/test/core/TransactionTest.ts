import { Buffer } from 'buffer';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { Address } from '../../src/net/bigtangle/core/Address';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { Transaction } from '../../src/net/bigtangle/core/Transaction';
import { FakeTxBuilder } from './FakeTxBuilder';
import {
    VerificationException,
    ScriptException,
} from '../../src/net/bigtangle/exception';
import { TransactionInput } from '../../src/net/bigtangle/core/TransactionInput';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { ScriptBuilder } from '../../src/net/bigtangle/script/ScriptBuilder';
import { TransactionOutput } from '../../src/net/bigtangle/core/TransactionOutput';
import { Coin } from '../../src/net/bigtangle/core/Coin';
import { Message } from '../../src/net/bigtangle/core/Message';
import { Script } from '../../src/net/bigtangle/script/Script';
import { MemoInfo } from '../../src/net/bigtangle/core/MemoInfo';
import { UTXO } from '../../src/net/bigtangle/core/UTXO';
import { Utils } from '../../src/net/bigtangle/utils/Utils';
import { BigInteger } from '../../src/net/bigtangle/core/BigInteger';

describe('TransactionTest', () => {
    const PARAMS = MainNetParams.get();
    const ADDRESS = ECKey.fromPrivate(new BigInteger('1')).toAddress(PARAMS);

    let tx: Transaction;

    beforeEach(() => {
        tx = FakeTxBuilder.createFakeTx(PARAMS);
    });

    test('duplicateOutPoint', () => {
        expect(() => {
            const input = tx.getInput(0);
            input.setScriptBytes(Buffer.from([1]));
            tx.addInput(input.duplicateDetached());
            tx.verify();
        }).toThrow(VerificationException.DuplicatedOutPoint);
    });

    test('coinbaseInputInNonCoinbaseTX', () => {
        expect(() => {
            tx.addInput(
                Sha256Hash.ZERO_HASH,
                Sha256Hash.ZERO_HASH,
                0xffffffff,
                new ScriptBuilder().data(Buffer.from(new Array(10).fill(0))).build(),
            );
            tx.verify();
        }).toThrow(VerificationException.UnexpectedCoinbaseInput);
    });

    test('coinbaseScriptSigTooSmall', () => {
        expect(() => {
            tx.clearInputs();
            tx.addInput(
                Sha256Hash.ZERO_HASH,
                Sha256Hash.ZERO_HASH,
                0xffffffff,
                new ScriptBuilder().build(),
            );
            tx.verify();
        }).toThrow(VerificationException.CoinbaseScriptSizeOutOfRange);
    });

    test('coinbaseScriptSigTooLarge', () => {
        expect(() => {
            tx.clearInputs();
            const input = tx.addInput(
                Sha256Hash.ZERO_HASH,
                Sha256Hash.ZERO_HASH,
                0xffffffff,
                new ScriptBuilder().data(Buffer.from(new Array(99).fill(0))).build(),
            );
            expect(input.getScriptBytes().length).toBe(101);
            tx.verify();
        }).toThrow(VerificationException.CoinbaseScriptSizeOutOfRange);
    });

    test('testOptimalEncodingMessageSize', () => {
        const tx = new Transaction(PARAMS);

        let length = tx.length;

        // add basic transaction input, check the length
        tx.addOutput(new TransactionOutput(PARAMS, null, Coin.COIN, ADDRESS));
        length += getCombinedLength(tx.getOutputs());

        // add basic output, check the length
        length += getCombinedLength(tx.getInputs());

        // optimal encoding size should equal the length we just calculated
        expect(tx.getOptimalEncodingMessageSize()).toBe(length);
    });

    function getCombinedLength(list: Message[]): number {
        let sumOfAllMsgSizes = 0;
        for (const m of list) {
            sumOfAllMsgSizes += m.getMessageSize() + 1;
        }
        return sumOfAllMsgSizes;
    }

    test('testCLTVPaymentChannelTransactionSpending', () => {
        const time = 20;

        const from = new ECKey(),
            to = new ECKey(),
            incorrect = new ECKey();
        const outputScript = ScriptBuilder.createCLTVPaymentChannelOutput(
            time,
            from,
            to,
        );

        const tx = new Transaction(PARAMS);
        tx.addInput(new TransactionInput(PARAMS, tx, Buffer.from([])));
        tx.getInput(0).setSequenceNumber(0);
        tx.setLockTime(time - 1);
        const fromSig = tx.calculateSignature(
            0,
            from,
            outputScript,
            Transaction.SigHash.SINGLE,
            false,
        );
        const toSig = tx.calculateSignature(
            0,
            to,
            outputScript,
            Transaction.SigHash.SINGLE,
            false,
        );
        const incorrectSig = tx.calculateSignature(
            0,
            incorrect,
            outputScript,
            Transaction.SigHash.SINGLE,
            false,
        );
        const scriptSig = ScriptBuilder.createCLTVPaymentChannelInput(
            fromSig,
            toSig,
        );
        const refundSig = ScriptBuilder.createCLTVPaymentChannelRefund(fromSig);
        const invalidScriptSig1 = ScriptBuilder.createCLTVPaymentChannelInput(
            fromSig,
            incorrectSig,
        );
        const invalidScriptSig2 = ScriptBuilder.createCLTVPaymentChannelInput(
            incorrectSig,
            toSig,
        );

        try {
            scriptSig.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
        } catch (e) {
            fail('Settle transaction failed to correctly spend the payment channel');
        }

        expect(() => {
            refundSig.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
        }).toThrow();
        expect(() => {
            invalidScriptSig1.correctlySpends(
                tx,
                0,
                outputScript,
                Script.ALL_VERIFY_FLAGS,
            );
        }).toThrow();
        expect(() => {
            invalidScriptSig2.correctlySpends(
                tx,
                0,
                outputScript,
                Script.ALL_VERIFY_FLAGS,
            );
        }).toThrow();
    });

    test('testCLTVPaymentChannelTransactionRefund', () => {
        const time = 20;

        const from = new ECKey(),
            to = new ECKey(),
            incorrect = new ECKey();
        const outputScript = ScriptBuilder.createCLTVPaymentChannelOutput(
            time,
            from,
            to,
        );

        const tx = new Transaction(PARAMS);
        tx.addInput(new TransactionInput(PARAMS, tx, Buffer.from([])));
        tx.getInput(0).setSequenceNumber(0);
        tx.setLockTime(time + 1);
        const fromSig = tx.calculateSignature(
            0,
            from,
            outputScript,
            Transaction.SigHash.SINGLE,
            false,
        );
        const incorrectSig = tx.calculateSignature(
            0,
            incorrect,
            outputScript,
            Transaction.SigHash.SINGLE,
            false,
        );
        const scriptSig = ScriptBuilder.createCLTVPaymentChannelRefund(fromSig);
        const invalidScriptSig =
            ScriptBuilder.createCLTVPaymentChannelRefund(incorrectSig);

        try {
            scriptSig.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
        } catch (e) {
            fail('Refund failed to correctly spend the payment channel');
        }

        expect(() => {
            invalidScriptSig.correctlySpends(
                tx,
                0,
                outputScript,
                Script.ALL_VERIFY_FLAGS,
            );
        }).toThrow();
    });

    test('testToStringWhenIteratingOverAnInputCatchesAnException', () => {
        const tx = FakeTxBuilder.createFakeTx(PARAMS);
        const ti = new (class extends TransactionInput {
            public getScriptSig(): Script {
                throw new ScriptException('');
            }
        })(PARAMS, tx, Buffer.from([]));

        tx.addInput(ti);
        expect(tx.toString().includes('[exception: ')).toBe(true);
    });

    test('testMemoUTXO', () => {
        tx.setMemo(new MemoInfo('Test:' + tx));
        const isCoinBase = tx.isCoinBase();
        for (const out of tx.getOutputs()) {
            const script = new Script(Buffer.from([]));
            let fromAddress = '';
            try {
                if (!isCoinBase) {
                    fromAddress = tx.getInputs()[0].getFromAddress().toBase58();
                }
            } catch (e) {
                // No address found.
            }
            let minsignnumber = 1;
            if (script.isSentToMultiSig()) {
                minsignnumber = script.getNumberOfSignaturesRequiredToSpend();
            }
            const newOut = new UTXO(
                tx.getHash(),
                out.getIndex(),
                out.getValue(),
                isCoinBase,
                script,
                '',
                null,
                fromAddress,
                tx.getMemo(),
                Utils.HEX.encode(out.getValue().getTokenid()),
                false,
                false,
                false,
                minsignnumber,
                0,
                Math.floor(Date.now() / 1000),
                null,
            );

            expect(newOut.getMemo().getMemo().includes('Test')).toBe(true);
        }
    });

    test('testAddSignedInputThrowsExceptionWhenScriptIsNotToRawPubKeyAndIsNotToAddress', () => {
        expect(() => {
            const key = new ECKey();
            const addr = key.toAddress(PARAMS);
            const fakeTx = FakeTxBuilder.createFakeTx(PARAMS, Coin.COIN, addr);

            const tx = new Transaction(PARAMS);
            tx.addOutput(fakeTx.getOutput(0));

            const script = ScriptBuilder.createOpReturnScript(Buffer.from([]));

            tx.addSignedInput(
                fakeTx.getOutput(0).getOutPointFor(Sha256Hash.ZERO_HASH),
                script,
                key,
            );
        }).toThrow(ScriptException);
    });

    test('optInFullRBF', () => {
        // a standard transaction as wallets would create
        const tx = FakeTxBuilder.createFakeTx(PARAMS);
        expect(tx.isOptInFullRBF()).toBe(false);

        tx.getInputs()[0].setSequenceNumber(TransactionInput.NO_SEQUENCE - 2);
        expect(tx.isOptInFullRBF()).toBe(true);
    });
});
