
import { Buffer } from 'buffer';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams.js';
import { Transaction } from '../../src/net/bigtangle/core/Transaction.js';
import { TransactionInput } from '../../src/net/bigtangle/core/TransactionInput.js';
import { TransactionOutput } from '../../src/net/bigtangle/core/TransactionOutput.js';
import { Coin } from '../../src/net/bigtangle/core/Coin.js';
// Adjust the import to match the actual export from ScriptOpCodes.js
import * as ScriptOpCodes from '../../src/net/bigtangle/script/ScriptOpCodes.js';
import { TransactionOutPoint } from '../../src/net/bigtangle/core/TransactionOutPoint.js';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash.js';
import { UtilsTest } from './UtilsTest.js';

describe('BlockTest', () => {
    const PARAMS = MainNetParams.get();

    test('testWork', () => {
        const genesisBlock = PARAMS.getGenesisBlock();
        if (!genesisBlock) {
            throw new Error('Genesis block is null');
        }
        const work = genesisBlock.getWork();
        // This number is printed by Bitcoin Core at startup as the calculated
        // value of chainWork on testnet:
        //
        // SetBestChain: new best=00000007199508e34a9f height=0 work=536879104
        expect(work).toBe(BigInt(536879104));
    });

    test.skip('testUpdateLength', () => {
        const params = MainNetParams.get();
        const block = UtilsTest.createBlock(
            PARAMS,
            params.getGenesisBlock(),
            params.getGenesisBlock(),
        );
        const origBlockLen = block.length;
        const tx = new Transaction(params);
        const outputScript = Buffer.alloc(10, ScriptOpCodes.OP_FALSE);
        tx.addOutput(new TransactionOutput(params, null, Coin.COIN, outputScript));
        tx.addInput(
            new TransactionInput(
                params,
                null,
                Buffer.from([ScriptOpCodes.OP_FALSE]),
                new TransactionOutPoint(
                    params,
                    0,
                    Sha256Hash.of(Buffer.from([1])),
                    Sha256Hash.of(Buffer.from([1])),
                ),
            ),
        );
        const origTxLength = 8 + 2 + 8 + 1 + 10 + 40 + 1 + 1; // TODO new length
        expect(tx.unsafeBitcoinSerialize().length).toBe(tx.length);
        expect(origTxLength).toBe(tx.length);
        block.addTransaction(tx);
        expect(block.unsafeBitcoinSerialize().length).toBe(block.length);
        expect(origBlockLen + tx.length).toBe(block.length);
        block
            .getTransactions()[1]
            .getInputs()[0]
            .setScriptBytes(
                Buffer.from([ScriptOpCodes.OP_FALSE, ScriptOpCodes.OP_FALSE]),
            );
        expect(block.length).toBe(origBlockLen + tx.length);
        expect(tx.length).toBe(origTxLength + 1);
        block.getTransactions()[1].getInputs()[0].clearScriptBytes();
        expect(block.length).toBe(block.unsafeBitcoinSerialize().length);
        expect(block.length).toBe(origBlockLen + tx.length);
        expect(tx.length).toBe(origTxLength - 1);
        block
            .getTransactions()[1]
            .addInput(
                new TransactionInput(
                    params,
                    null,
                    Buffer.from([ScriptOpCodes.OP_FALSE]),
                    new TransactionOutPoint(
                        params,
                        0,
                        Sha256Hash.of(Buffer.from([1])),
                        Sha256Hash.of(Buffer.from([1])),
                    ),
                ),
            );
        expect(block.length).toBe(origBlockLen + tx.length);
        expect(tx.length).toBe(origTxLength + 41); // - 1 + 40 + 1 + 1
    });
});
