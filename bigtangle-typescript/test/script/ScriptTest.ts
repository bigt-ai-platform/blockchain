import { Buffer } from 'buffer';
import { Script } from '../../src/net/bigtangle/script/Script';
import { ScriptBuilder } from '../../src/net/bigtangle/script/ScriptBuilder';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { Address } from '../../src/net/bigtangle/core/Address';
import { Utils } from '../../src/net/bigtangle/utils/Utils';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { Transaction } from '../../src/net/bigtangle/core/Transaction';
import { TransactionInput } from '../../src/net/bigtangle/core/TransactionInput';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { TransactionSignature } from '../../src/net/bigtangle/crypto/TransactionSignature';
import { OP_0 } from '../../src/net/bigtangle/script/ScriptOpCodes';
import { ScriptException } from '../../src/net/bigtangle/exception';
import { BigInteger } from '../../src/net/bigtangle/core/BigInteger';

describe('ScriptTest', () => {
    const sigProg =
        '47304402202b4da291cc39faf8433911988f9f49fc5c995812ca2f94db61468839c228c3e90220628bff3ff32ec95825092fa051cba28558a981fcf59ce184b14f2e215e69106701410414b38f4be3bb9fa0f4f32b74af07152b2f2f630bc02122a491137b6c523e46f18a0d5034418966f93dfc37cc3739ef7b2007213a302b7fba161557f4ad644a1c';
    const pubkeyProg = '76a91433e81a941e64cda12c6a299ed322ddbdd03f8d0e88ac';
    const PARAMS = MainNetParams.get();

    test('testScriptSig', () => {
        const sigProgBytes = Utils.HEX.decode(sigProg);
        const script = new Script(sigProgBytes);
        const hash160 = Utils.sha256hash160(script.getPubKey());
        const a = new Address(PARAMS, hash160);
        expect(a.toString()).toBe('15jTWe6r9zqxkjjLFntAWADZosAwiuw4U5');
    });

    test('testScriptPubKey', () => {
        const pubkeyBytes = Utils.HEX.decode(pubkeyProg);
        const pubkey = new Script(pubkeyBytes);
        expect(pubkey.toString()).toBe(
            'DUP HASH160 PUSHDATA(20)[33e81a941e64cda12c6a299ed322ddbdd03f8d0e] EQUALVERIFY CHECKSIG',
        );
        const toAddr = new Address(PARAMS, pubkey.getPubKeyHash());
        expect(toAddr.toString()).toBe('15jTWe6r9zqxkjjLFntAWADZosAwiuw4U5');
    });

    test('testMultiSig', () => {
        const keys = [ECKey.fromPrivate(new BigInteger('1')), ECKey.fromPrivate(new BigInteger('2')), ECKey.fromPrivate(new BigInteger('3'))];
        expect(
            ScriptBuilder.createMultiSigOutputScript(2, keys).isSentToMultiSig(),
        ).toBe(true);
        const script = ScriptBuilder.createMultiSigOutputScript(3, keys);
        expect(script.isSentToMultiSig()).toBe(true);
        const pubkeys: ECKey[] = [];
        for (const key of keys) {
            pubkeys.push(ECKey.fromPublic(key.getPubKeyPoint()));
        }
        expect(script.getPubKeys()).toEqual(pubkeys);
        expect(
            ScriptBuilder.createOutputScript(ECKey.fromPrivate(new BigInteger('4'))).isSentToMultiSig(),
        ).toBe(false);
        try {
            Script.createMultiSigOutputScript(4, keys);
            fail();
        } catch (e) {
            // Expected.
        }
        try {
            Script.createMultiSigOutputScript(0, keys);
        } catch (e) {
            // Expected.
        }
    });

    test('testP2SHOutputScript', () => {
        const p2shAddress = Address.fromBase58(
            MainNetParams.get(),
            '35b9vsyH1KoFT5a5KtrKusaCcPLkiSo1tU',
        );
        expect(
            ScriptBuilder.createOutputScript(p2shAddress).isPayToScriptHash(),
        ).toBe(true);
    });

    test('testIp', () => {
        const bytes = Utils.HEX.decode(
            '41043e96222332ea7848323c08116dddafbfa917b8e37f0bdf63841628267148588a09a43540942d58d49717ad3fabfe14978cf4f0a8b84d2435dad16e9aa4d7f935ac',
        );
        const s = new Script(bytes);
        expect(s.isSentToRawPubKey()).toBe(true);
    });

    test('createAndUpdateEmptyInputScript', () => {
        const dummySig = TransactionSignature.dummy();
        const key = new ECKey();

        let inputScript = ScriptBuilder.createInputScript(dummySig);
        expect(
            Buffer.compare(
                inputScript.getChunks()[0].data,
                dummySig.encodeToBitcoin(),
            ),
        ).toBe(0);
        inputScript = ScriptBuilder.createInputScript(null);
        expect(inputScript.getChunks()[0].opcode).toBe(OP_0);

        inputScript = ScriptBuilder.createInputScript(dummySig, key);
        expect(
            Buffer.compare(
                inputScript.getChunks()[0].data,
                dummySig.encodeToBitcoin(),
            ),
        ).toBe(0);
        inputScript = ScriptBuilder.createInputScript(null, key);
        expect(inputScript.getChunks()[0].opcode).toBe(OP_0);
        expect(Buffer.compare(inputScript.getChunks()[1].data, key.getPubKey())).toBe(
            0,
        );

        const key2 = new ECKey();
        const multisigScript = ScriptBuilder.createMultiSigOutputScript(2, [
            key,
            key2,
        ]);
        inputScript = ScriptBuilder.createP2SHMultiSigInputScript(
            [dummySig, dummySig],
            multisigScript,
        );
        expect(inputScript.getChunks()[0].opcode).toBe(OP_0);
        expect(
            Buffer.compare(
                inputScript.getChunks()[1].data,
                dummySig.encodeToBitcoin(),
            ),
        ).toBe(0);
        expect(
            Buffer.compare(
                inputScript.getChunks()[2].data,
                dummySig.encodeToBitcoin(),
            ),
        ).toBe(0);
        expect(
            Buffer.compare(
                inputScript.getChunks()[3].data,
                multisigScript.getProgram(),
            ),
        ).toBe(0);

        inputScript = ScriptBuilder.createP2SHMultiSigInputScript(
            null,
            multisigScript,
        );
        expect(inputScript.getChunks()[0].opcode).toBe(OP_0);
        expect(inputScript.getChunks()[1].opcode).toBe(OP_0);
        expect(inputScript.getChunks()[2].opcode).toBe(OP_0);
        expect(
            Buffer.compare(
                inputScript.getChunks()[3].data,
                multisigScript.getProgram(),
            ),
        ).toBe(0);

        inputScript = ScriptBuilder.updateScriptWithSignature(
            inputScript,
            dummySig.encodeToBitcoin(),
            0,
            1,
            1,
        );
        expect(inputScript.getChunks()[0].opcode).toBe(OP_0);
        expect(
            Buffer.compare(
                inputScript.getChunks()[1].data,
                dummySig.encodeToBitcoin(),
            ),
        ).toBe(0);
        expect(inputScript.getChunks()[2].opcode).toBe(OP_0);
        expect(
            Buffer.compare(
                inputScript.getChunks()[3].data,
                multisigScript.getProgram(),
            ),
        ).toBe(0);

        inputScript = ScriptBuilder.updateScriptWithSignature(
            inputScript,
            dummySig.encodeToBitcoin(),
            1,
            1,
            1,
        );
        expect(inputScript.getChunks()[0].opcode).toBe(OP_0);
        expect(
            Buffer.compare(
                inputScript.getChunks()[1].data,
                dummySig.encodeToBitcoin(),
            ),
        ).toBe(0);
        expect(
            Buffer.compare(
                inputScript.getChunks()[2].data,
                dummySig.encodeToBitcoin(),
            ),
        ).toBe(0);
        expect(
            Buffer.compare(
                inputScript.getChunks()[3].data,
                multisigScript.getProgram(),
            ),
        ).toBe(0);

        expect(() => {
            ScriptBuilder.updateScriptWithSignature(
                inputScript,
                dummySig.encodeToBitcoin(),
                1,
                1,
                1,
            );
        }).toThrow();
    });

    test('testOp0', () => {
        const tx = new Transaction(PARAMS);
        tx.addInput(new TransactionInput(PARAMS, tx, Buffer.from([])));
        const script = new ScriptBuilder().smallNum(0).build();

        const stack: Buffer[] = [];
        Script.executeScript(tx, 0, script, stack, Script.ALL_VERIFY_FLAGS);
        expect(stack[0].length).toBe(0);
    });

    test('testCLTVPaymentChannelOutput', () => {
        const script = ScriptBuilder.createCLTVPaymentChannelOutput(
            BigInt(20),
            new ECKey(),
            new ECKey(),
        );
        expect(script.isSentToCLTVPaymentChannel()).toBe(true);
    });

    test('getToAddress', () => {
        const toKey = new ECKey();
        const toAddress = toKey.toAddress(PARAMS);
        expect(
            ScriptBuilder.createOutputScript(toKey).getToAddress(PARAMS, true),
        ).toEqual(toAddress);
        expect(
            ScriptBuilder.createOutputScript(toAddress).getToAddress(PARAMS, true),
        ).toEqual(toAddress);
        const p2shScript = ScriptBuilder.createP2SHOutputScript(Buffer.alloc(20));
        const scriptAddress = Address.fromP2SHScript(PARAMS, p2shScript);
        expect(p2shScript.getToAddress(PARAMS, true)).toEqual(scriptAddress);
    });

    test('getToAddressNoPubKey', () => {
        expect(() => {
            ScriptBuilder.createOutputScript(new ECKey()).getToAddress(PARAMS, false);
        }).toThrow(ScriptException);
    });

    test('numberBuilderZero', () => {
        const builder = new ScriptBuilder();
        builder.number(0);
        expect(
            Buffer.compare(builder.build().getProgram(), Buffer.from([0x00])),
        ).toBe(0);
    });

    test('numberBuilderPositiveOpCode', () => {
        const builder = new ScriptBuilder();
        builder.number(5);
        expect(
            Buffer.compare(builder.build().getProgram(), Buffer.from([0x55])),
        ).toBe(0);
    });

    test('numberBuilderBigNum', () => {
        let builder = new ScriptBuilder();
        builder.number(0x524a);
        expect(
            Buffer.compare(
                builder.build().getProgram(),
                Buffer.from([0x02, 0x4a, 0x52]),
            ),
        ).toBe(0);

        builder = new ScriptBuilder();
        builder.number(0x110011);
        expect(builder.build().getProgram().length).toBe(4);

        builder = new ScriptBuilder();
        builder.number(0x8000);
        expect(
            Buffer.compare(
                builder.build().getProgram(),
                Buffer.from([0x03, 0x00, 0x80, 0x00]),
            ),
        ).toBe(0);
    });

    test('numberBuilderNegative', () => {
        const builder = new ScriptBuilder();
        builder.number(-5);
        expect(
            Buffer.compare(
                builder.build().getProgram(),
                Buffer.from([0x01, 133]),
            ),
        ).toBe(0);
    });

    test('numberBuilder16', () => {
        const builder = new ScriptBuilder();
        builder.number(15).number(16).number(17);
        builder.number(0, 17).number(1, 16).number(2, 15);
        const script = builder.build();
        expect(script.toString()).toBe(
            'PUSHDATA(1)[11] 16 15 15 16 PUSHDATA(1)[11]',
        );
    });
});
