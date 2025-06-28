import { ScriptChunk } from './ScriptChunk.js';
import { Script } from './Script.js';
import { Address } from '../core/Address.js';
import { ECKey } from '../core/ECKey.js';
import { Utils } from '../utils/Utils.js';
import { TransactionSignature } from '../crypto/TransactionSignature.js';
import { BigInteger } from '../core/BigInteger.js';

// Re-export ScriptOpCodes from Script.ts or define them here if not in Script.ts
// For now, assuming they will be defined in Script.ts or a separate ScriptOpCodes.ts
// For simplicity, I'll define the necessary ones here for now.
const OP_0 = 0x00;
const OP_PUSHDATA1 = 0x4c;
const OP_PUSHDATA2 = 0x4d;
const OP_PUSHDATA4 = 0x4e;
const OP_DUP = 0x76;
const OP_HASH160 = 0xa9;
const OP_EQUAL = 0x87;
const OP_EQUALVERIFY = 0x88;
const OP_CHECKSIG = 0xac;
const OP_CHECKMULTISIG = 0xae;
const OP_RETURN = 0x6a;
const OP_IF = 0x63;
const OP_ELSE = 0x67;
const OP_ENDIF = 0x68;
const OP_CHECKLOCKTIMEVERIFY = 0xb1;
const OP_DROP = 0x75;
const OP_CHECKSIGVERIFY = 0xad;
const OP_CODESEPARATOR = 0xab; // Used in Script.removeAllInstancesOfOp

export class ScriptBuilder {
    private chunks: ScriptChunk[];

    constructor(template?: Script) {
        this.chunks = template ? [...template.getChunks()] : [];
    }

    addChunk(chunk: ScriptChunk): ScriptBuilder {
        this.chunks.push(chunk);
        return this;
    }

    op(opcode: number): ScriptBuilder {
        if (opcode <= OP_PUSHDATA4) {
            throw new Error("Opcode must be greater than OP_PUSHDATA4 for direct opcode addition.");
        }
        return this.addChunk(new ScriptChunk(opcode, null));
    }

    data(data: Uint8Array): ScriptBuilder {
        if (data.length === 0) {
            return this.smallNum(0);
        } else {
            return this.addDataChunk(data);
        }
    }

    private addDataChunk(data: Uint8Array): ScriptBuilder {
        let opcode: number;
        if (data.length === 0) {
            opcode = OP_0;
        } else if (data.length === 1) {
            const b = data[0];
            if (b >= 1 && b <= 16) {
                opcode = Script.encodeToOpN(b);
            } else {
                opcode = 1;
            }
        } else if (data.length < OP_PUSHDATA1) {
            opcode = data.length;
        } else if (data.length < 256) {
            opcode = OP_PUSHDATA1;
        } else if (data.length < 65536) {
            opcode = OP_PUSHDATA2;
        } else {
            throw new Error("Unimplemented: Data length too large for PUSHDATA opcodes.");
        }
        return this.addChunk(new ScriptChunk(opcode, data));
    }

    number(num: number | BigInteger): ScriptBuilder {
        if (typeof num === 'number' && num >= 0 && num <= 16) {
            return this.smallNum(num);
        } else {
            return this.bigNum(num);
        }
    }

    smallNum(num: number): ScriptBuilder {
        if (num < 0 || num > 16) {
            throw new Error("Cannot encode numbers outside 0-16 with smallNum");
        }
        return this.addChunk(new ScriptChunk(Script.encodeToOpN(num), null));
    }

    protected bigNum(num: number | BigInteger): ScriptBuilder {
        let data: Uint8Array;

        if (typeof num === 'number' && num === 0) {
            data = new Uint8Array(0);
        } else {
            let absValue: BigInteger;
            let neg = false;
            if (typeof num === 'number') {
                neg = num < 0;
                absValue = new BigInteger(String(Math.abs(num)));
            } else { // BigInteger
                neg = num.signum() === -1;
                absValue = num.abs();
            }

            const result: number[] = [];
            while (absValue.compareTo(BigInteger.ZERO) !== 0) {
                result.push(absValue.and(new BigInteger("0xff")).intValue());
                absValue = absValue.shiftRight(8);
            }

            if ((result[result.length - 1] & 0x80) !== 0) {
                result.push(neg ? 0x80 : 0x00);
            } else if (neg) {
                result[result.length - 1] |= 0x80;
            }
            data = new Uint8Array(result);
        }
        return this.addChunk(new ScriptChunk(data.length, data));
    }

    build(): Script {
        return new Script(this.chunks);
    }

    static createOutputScript(to: Address | ECKey): Script {
        if (to instanceof Address) {
            if (to.isP2SHAddress()) {
                return new ScriptBuilder()
                    .op(OP_HASH160)
                    .data(to.getHash160())
                    .op(OP_EQUAL)
                    .build();
            } else {
                return new ScriptBuilder()
                    .op(OP_DUP)
                    .op(OP_HASH160)
                    .data(to.getHash160())
                    .op(OP_EQUALVERIFY)
                    .op(OP_CHECKSIG)
                    .build();
            }
        } else if (to instanceof ECKey) {
            return new ScriptBuilder().data(to.getPubKey()).op(OP_CHECKSIG).build();
        }
        throw new Error("Invalid type for createOutputScript");
    }

    static createInputScript(signature: TransactionSignature | null, pubKey?: ECKey): Script {
        const sigBytes = signature !== null ? signature.encodeToBitcoin() : new Uint8Array();
        const builder = new ScriptBuilder().data(sigBytes);
        if (pubKey) {
            builder.data(pubKey.getPubKey());
        }
        return builder.build();
    }

    static createMultiSigOutputScript(threshold: number, pubkeys: ECKey[]): Script {
        if (threshold <= 0 || threshold > pubkeys.length || pubkeys.length > 16) {
            throw new Error("Invalid threshold or number of public keys for multisig.");
        }
        const builder = new ScriptBuilder();
        builder.smallNum(threshold);
        // Use the order as given (do not sort)
        for (const key of pubkeys) {
            builder.data(key.getPubKey());
        }
        builder.smallNum(pubkeys.length);
        builder.op(OP_CHECKMULTISIG);
        return builder.build();
    }

    static createMultiSigInputScript(signatures: TransactionSignature[] | Uint8Array[], multisigProgramBytes?: Uint8Array): Script {
        const builder = new ScriptBuilder();
        builder.smallNum(0); // Work around a bug in CHECKMULTISIG
        for (const sig of signatures) {
            if (sig instanceof Uint8Array) {
                builder.data(sig);
            } else {
                builder.data(sig.encodeToBitcoin());
            }
        }
        if (multisigProgramBytes) {
            builder.data(multisigProgramBytes);
        }
        return builder.build();
    }

    static createP2SHMultiSigInputScript(signatures: TransactionSignature[] | null, multisigProgram: Script): Script {
        const sigs: Uint8Array[] = [];
        if (signatures === null) {
            const numSigs = multisigProgram.getNumberOfSignaturesRequiredToSpend();
            for (let i = 0; i < numSigs; i++) {
                sigs.push(new Uint8Array()); // Empty signature placeholder
            }
        } else {
            for (const signature of signatures) {
                sigs.push(signature.encodeToBitcoin());
            }
        }
        return ScriptBuilder.createMultiSigInputScript(sigs, multisigProgram.getProgram());
    }

    static updateScriptWithSignature(scriptSig: Script, signature: Uint8Array, targetIndex: number, sigsPrefixCount: number, sigsSuffixCount: number): Script {
        const builder = new ScriptBuilder();
        const inputChunks = scriptSig.getChunks();
        const totalChunks = inputChunks.length;

        const hasMissingSigs = inputChunks[totalChunks - sigsSuffixCount - 1].equalsOpCode(OP_0);
        if (!hasMissingSigs) {
            throw new Error("ScriptSig is already filled with signatures");
        }

        for (let i = 0; i < sigsPrefixCount; i++) {
            builder.addChunk(inputChunks[i]);
        }

        let pos = 0;
        let inserted = false;
        for (let i = sigsPrefixCount; i < totalChunks - sigsSuffixCount; i++) {
            const chunk = inputChunks[i];
            if (pos === targetIndex) {
                inserted = true;
                builder.data(signature);
                pos++;
            }
            if (!chunk.equalsOpCode(OP_0)) {
                builder.addChunk(chunk);
                pos++;
            }
        }

        while (pos < totalChunks - sigsPrefixCount - sigsSuffixCount) {
            if (pos === targetIndex) {
                inserted = true;
                builder.data(signature);
            } else {
                builder.addChunk(new ScriptChunk(OP_0, null));
            }
            pos++;
        }

        for (let i = totalChunks - sigsSuffixCount; i < totalChunks; i++) {
            builder.addChunk(inputChunks[i]);
        }

        if (!inserted) {
            throw new Error("Signature not inserted. This should not happen.");
        }
        return builder.build();
    }

    static createP2SHOutputScript(hash: Uint8Array): Script {
        if (hash.length !== 20) {
            throw new Error("Hash must be 20 bytes long for P2SH output script.");
        }
        return new ScriptBuilder().op(OP_HASH160).data(hash).op(OP_EQUAL).build();
    }

    static createP2SHOutputScriptFromScript(redeemScript: Script): Script {
        const hash = Utils.sha256hash160(redeemScript.getProgram());
        return ScriptBuilder.createP2SHOutputScript(hash);
    }

    static createP2SHOutputScriptWithKeys(threshold: number, pubkeys: ECKey[]): Script {
        const redeemScript = ScriptBuilder.createRedeemScript(threshold, pubkeys);
        return ScriptBuilder.createP2SHOutputScriptFromScript(redeemScript);
    }

    static createRedeemScript(threshold: number, pubkeys: ECKey[]): Script {
        const sortedPubkeys = [...pubkeys].sort((a, b) => {
            const pubA = a.getPubKey();
            const pubB = b.getPubKey();
            for (let i = 0; i < Math.min(pubA.length, pubB.length); i++) {
                if (pubA[i] !== pubB[i]) {
                    return pubA[i] - pubB[i];
                }
            }
            return pubA.length - pubB.length;
        });
        return ScriptBuilder.createMultiSigOutputScript(threshold, sortedPubkeys);
    }

    static createOpReturnScript(data: Uint8Array): Script {
        if (data.length > 80) {
            throw new Error("Data too long for OP_RETURN script (max 80 bytes).");
        }
        return new ScriptBuilder().op(OP_RETURN).data(data).build();
    }

    static createCLTVPaymentChannelOutput(time: BigInteger, from: ECKey, to: ECKey): Script {
        const timeBytes = Utils.reverseBytes(Utils.encodeMPI(time, false)); // Assuming encodeMPI exists
        if (timeBytes.length > 5) {
            throw new Error("Time too large to encode as 5-byte int");
        }
        return new ScriptBuilder().op(OP_IF)
            .data(to.getPubKey()).op(OP_CHECKSIGVERIFY) // Assuming OP_CHECKSIGVERIFY exists
            .op(OP_ELSE)
            .data(timeBytes).op(OP_CHECKLOCKTIMEVERIFY).op(OP_DROP)
            .op(OP_ENDIF)
            .data(from.getPubKey()).op(OP_CHECKSIG).build();
    }

    static createCLTVPaymentChannelRefund(signature: TransactionSignature): Script {
        const builder = new ScriptBuilder();
        builder.data(signature.encodeToBitcoin());
        builder.data(new Uint8Array([0])); // Use the CHECKLOCKTIMEVERIFY if branch
        return builder.build();
    }

    static createCLTVPaymentChannelP2SHRefund(signature: TransactionSignature, redeemScript: Script): Script {
        const builder = new ScriptBuilder();
        builder.data(signature.encodeToBitcoin());
        builder.data(new Uint8Array([0])); // Use the CHECKLOCKTIMEVERIFY if branch
        builder.data(redeemScript.getProgram());
        return builder.build();
    }

    static createCLTVPaymentChannelP2SHInput(from: Uint8Array, to: Uint8Array, redeemScript: Script): Script {
        const builder = new ScriptBuilder();
        builder.data(from);
        builder.data(to);
        builder.smallNum(1); // Use the CHECKLOCKTIMEVERIFY if branch
        builder.data(redeemScript.getProgram());
        return builder.build();
    }

    static createCLTVPaymentChannelInput(from: TransactionSignature | Uint8Array, to: TransactionSignature | Uint8Array): Script {
        const fromBytes = from instanceof TransactionSignature ? from.encodeToBitcoin() : from;
        const toBytes = to instanceof TransactionSignature ? to.encodeToBitcoin() : to;
        const builder = new ScriptBuilder();
        builder.data(fromBytes);
        builder.data(toBytes);
        builder.smallNum(1); // Use the CHECKLOCKTIMEVERIFY if branch
        return builder.build();
    }
}
