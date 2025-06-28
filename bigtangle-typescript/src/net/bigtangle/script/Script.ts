import { NetworkParameters } from '../core/NetworkParameters.js';
import { Sha256Hash } from '../core/Sha256Hash.js';
import { Address } from '../core/Address.js';
import { ECKey } from '../core/ECKey.js';
import { Transaction } from '../core/Transaction.js';
import { TransactionSignature } from '../crypto/TransactionSignature.js';
import { ScriptException } from '../exception/ScriptException.js';
import { Utils } from '../utils/Utils.js';
import { ScriptChunk } from './ScriptChunk.js';
import { BigInteger } from '../core/BigInteger.js';
import {
    OP_0, OP_PUSHDATA1, OP_PUSHDATA2, OP_PUSHDATA4, OP_1NEGATE, OP_1, OP_2, OP_3, OP_4, OP_5, OP_6, OP_7, OP_8, OP_9, OP_10, OP_11, OP_12, OP_13, OP_14, OP_15, OP_16,
    OP_NOP, OP_IF, OP_NOTIF, OP_VERIF, OP_VERNOTIF, OP_ELSE, OP_ENDIF, OP_VERIFY, OP_RETURN,
    OP_TOALTSTACK, OP_FROMALTSTACK, OP_2DROP, OP_2DUP, OP_3DUP, OP_2OVER, OP_2ROT, OP_2SWAP, OP_IFDUP, OP_DEPTH, OP_DROP, OP_DUP, OP_NIP, OP_OVER, OP_PICK, OP_ROLL, OP_ROT, OP_SWAP, OP_TUCK,
    OP_CAT, OP_SUBSTR, OP_LEFT, OP_RIGHT, OP_SIZE,
    OP_INVERT, OP_AND, OP_OR, OP_XOR, OP_EQUAL, OP_EQUALVERIFY, OP_RESERVED1, OP_RESERVED2,
    OP_1ADD, OP_1SUB, OP_2MUL, OP_2DIV, OP_NEGATE, OP_ABS, OP_NOT, OP_0NOTEQUAL, OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MOD, OP_LSHIFT, OP_RSHIFT, OP_BOOLAND, OP_BOOLOR, OP_NUMEQUAL, OP_NUMEQUALVERIFY, OP_NUMNOTEQUAL, OP_LESSTHAN, OP_GREATERTHAN, OP_LESSTHANOREQUAL, OP_GREATERTHANOREQUAL, OP_MIN, OP_MAX, OP_WITHIN,
    OP_RIPEMD160, OP_SHA1, OP_SHA256, OP_HASH160, OP_HASH256, OP_CODESEPARATOR, OP_CHECKSIG, OP_CHECKSIGVERIFY, OP_CHECKMULTISIG, OP_CHECKMULTISIGVERIFY,
    OP_CHECKLOCKTIMEVERIFY, OP_NOP1, OP_NOP3, OP_NOP4, OP_NOP5, OP_NOP6, OP_NOP7, OP_NOP8, OP_NOP9, OP_NOP10, OP_INVALIDOPCODE,
} from './ScriptOpCodes.js';
import { DataOutputStream } from '../utils/DataOutputStream.js';
import { UnsafeByteArrayOutputStream } from '../core/UnsafeByteArrayOutputStream.js';

/**
 * <p>Programs embedded inside transactions that control redemption of payments.</p>
 *
 * <p>Bitcoin transactions don't specify what they do directly. Instead <a href="https://en.bitcoin.it/wiki/Script">a
 * small binary stack language</a> is used to define programs that when evaluated return whether the transaction
 * "accepts" or rejects the other transactions connected to it.</p>
 *
 * <p>In SPV mode, scripts are not run, because that would require all transactions to be available and lightweight
 * clients don't have that data. In full mode, this class is used to run the interpreted language. It also has
 * static methods for building scripts.</p>
 */
export class Script {

    // The program is a set of chunks where each element is either [opcode] or [data, data, data ...]
    protected chunks!: ScriptChunk[];
    // Unfortunately, scripts are not ever re-serialized or canonicalized when used in signature hashing. Thus we
    // must preserve the exact bytes that we read off the wire, along with the parsed form.
    protected program: Uint8Array;

    // Creation time of the associated keys in seconds since the epoch.
    private creationTimeSeconds: number;

    private static readonly log = console; // LoggerFactory.getLogger(Script.class);
    static readonly MAX_SCRIPT_ELEMENT_SIZE = 520;  // bytes
    static readonly SIG_SIZE = 75;
    /** Max number of sigops allowed in a standard p2sh redeem script */
    static readonly MAX_P2SH_SIGOPS = 15;

    private static readonly STANDARD_TRANSACTION_SCRIPT_CHUNKS: ScriptChunk[] = [
        new ScriptChunk(OP_DUP, null, 0),
        new ScriptChunk(OP_HASH160, null, 1),
        new ScriptChunk(OP_EQUALVERIFY, null, 23),
        new ScriptChunk(OP_CHECKSIG, null, 24),
    ];

    /** Creates an empty script that serializes to nothing. */
    constructor();
    // Used from ScriptBuilder.
    constructor(chunks: ScriptChunk[]);
    /**
     * Construct a Script that copies and wraps the programBytes array. The array is parsed and checked for syntactic
     * validity.
     * @param programBytes Array of program bytes from a transaction.
     */
    constructor(programBytes: Uint8Array);
    constructor(programBytes: Uint8Array, creationTimeSeconds: number);
    constructor(param1?: ScriptChunk[] | Uint8Array, param2?: number) {
        if (param1 === undefined) {
            this.chunks = [];
            this.program = new Uint8Array();
            this.creationTimeSeconds = Utils.currentTimeSeconds();
        } else if (Array.isArray(param1)) {
            this.chunks = [...param1]; // Copy the array
            this.program = this.getProgram(); // Generate program bytes from chunks
            this.creationTimeSeconds = Utils.currentTimeSeconds();
        } else if (param1 instanceof Uint8Array) {
            this.program = param1;
            this.parse(param1);
            this.creationTimeSeconds = param2 !== undefined ? param2 : 0;
        } else {
            throw new Error("Invalid constructor arguments for Script");
        }
    }

    getCreationTimeSeconds(): number {
        return this.creationTimeSeconds;
    }

    setCreationTimeSeconds(creationTimeSeconds: number): void {
        this.creationTimeSeconds = creationTimeSeconds;
    }

    /**
     * Returns the program opcodes as a string, for example "[1234] DUP HASH160"
     */
    toString(): string {
        return this.chunks.map(chunk => chunk.toString()).join(' ');
    }

    /** Returns the serialized program as a newly created byte array. */
    getProgram(): Uint8Array {
        // Don't round-trip as Bitcoin Core doesn't and it would introduce a mismatch.
        if (this.program != null && this.program.length > 0) {
            return new Uint8Array(this.program);
        }
        // const bos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream();
        for (const chunk of this.chunks) {
            chunk.write(dos);
        }
        // this.program = bos.toByteArray();
        this.program = dos.toByteArray();
        return this.program;
    }

    /** Returns an immutable list of the scripts parsed form. Each chunk is either an opcode or data element. */
    getChunks(): ScriptChunk[] {
        return [...this.chunks]; // Return a copy to make it immutable
    }

    /**
     * <p>To run a script, first we parse it which breaks it up into chunks representing pushes of data or logical
     * opcodes. Then we can run the parsed chunks.</p>
     *
     * <p>The reason for this split, instead of just interpreting directly, is to make it easier
     * to reach into a programs structure and pull out bits of data without having to run it.
     * This is necessary to render the to/from addresses of transactions in a user interface.
     * Bitcoin Core does something similar.</p>
     */
    private parse(program: Uint8Array): void {
        this.chunks = [];
        let cursor = 0;
        const initialSize = program.length;

        while (cursor < initialSize) {
            const startLocationInProgram = cursor;
            const opcode = program[cursor++] & 0xFF;

            let dataToRead = -1;
            if (opcode >= 0 && opcode < OP_PUSHDATA1) {
                dataToRead = opcode;
            } else if (opcode === OP_PUSHDATA1) {
                if (cursor >= initialSize) throw new ScriptException("Unexpected end of script");
                dataToRead = program[cursor++] & 0xFF;
            } else if (opcode === OP_PUSHDATA2) {
                if (cursor + 1 >= initialSize) throw new ScriptException("Unexpected end of script");
                dataToRead = (program[cursor] & 0xFF) | ((program[cursor + 1] & 0xFF) << 8);
                cursor += 2;
            } else if (opcode === OP_PUSHDATA4) {
                if (cursor + 3 >= initialSize) throw new ScriptException("Unexpected end of script");
                dataToRead = (program[cursor] & 0xFF) | ((program[cursor + 1] & 0xFF) << 8) |
                             ((program[cursor + 2] & 0xFF) << 16) | ((program[cursor + 3] & 0xFF) << 24);
                cursor += 4;
            }

            let chunk: ScriptChunk;
            if (dataToRead === -1) {
                chunk = new ScriptChunk(opcode, null, startLocationInProgram);
            } else {
                if (dataToRead > (initialSize - cursor)) {
                    throw new ScriptException("Push of data element that is larger than remaining data");
                }
                const data = program.slice(cursor, cursor + dataToRead);
                cursor += dataToRead;
                chunk = new ScriptChunk(opcode, data, startLocationInProgram);
            }
            // Save some memory by eliminating redundant copies of the same chunk objects.
            for (const c of Script.STANDARD_TRANSACTION_SCRIPT_CHUNKS) {
                if (c.equals(chunk)) chunk = c;
            }
            this.chunks.push(chunk);
        }
    }

    /**
     * Returns true if this script is of the form <pubkey> OP_CHECKSIG. This form was originally intended for transactions
     * where the peers talked to each other directly via TCP/IP, but has fallen out of favor with time due to that mode
     * of operation being susceptible to man-in-the-middle attacks. It is still used in coinbase outputs and can be
     * useful more exotic types of transaction, but today most payments are to addresses.
     */
    isSentToRawPubKey(): boolean {
        return this.chunks.length === 2 && this.chunks[1].equalsOpCode(OP_CHECKSIG) &&
               !this.chunks[0].isOpCode() && (this.chunks[0].data?.length || 0) > 1;
    }

    /**
     * Returns true if this script is of the form DUP HASH160 <pubkey hash> EQUALVERIFY CHECKSIG, ie, payment to an
     * address like 1VayNert3x1KzbpzMGt2qdqrAThiRovi8. This form was originally intended for the case where you wish
     * to send somebody money with a written code because their node is offline, but over time has become the standard
     * way to make payments due to the short and recognizable base58 form addresses come in.
     */
    isSentToAddress(): boolean {
        return this.chunks.length === 5 &&
               this.chunks[0].equalsOpCode(OP_DUP) &&
               this.chunks[1].equalsOpCode(OP_HASH160) &&
               (this.chunks[2].data?.length ?? 0) === 20 &&
               this.chunks[3].equalsOpCode(OP_EQUALVERIFY) &&
               this.chunks[4].equalsOpCode(OP_CHECKSIG);
    }

    /**
     * An alias for isPayToScriptHash.
     */
    isSentToP2SH(): boolean {
        return this.isPayToScriptHash();
    }

    /**
     * <p>If a program matches the standard template DUP HASH160 <pubkey hash> EQUALVERIFY CHECKSIG
     * then this function retrieves the third element.
     * In this case, this is useful for fetching the destination address of a transaction.</p>
     *
     * <p>If a program matches the standard template HASH160 <script hash> EQUAL
     * then this function retrieves the second element.
     * In this case, this is useful for fetching the hash of the redeem script of a transaction.</p>
     *
     * <p>Otherwise it throws a ScriptException.</p>
     *
     */
    getPubKeyHash(): Uint8Array {
        if (this.isSentToAddress()) {
            if (!this.chunks[2].data) throw new ScriptException("No data in chunk 2 for P2PKH script");
            return this.chunks[2].data;
        } else if (this.isPayToScriptHash()) {
            if (!this.chunks[1].data) throw new ScriptException("No data in chunk 1 for P2SH script");
            return this.chunks[1].data;
        } else {
            throw new ScriptException("Script not in the standard scriptPubKey form");
        }
    }

    /**
     * Returns the public key in this script. If a script contains two constants and nothing else, it is assumed to
     * be a scriptSig (input) for a pay-to-address output and the second constant is returned (the first is the
     * signature). If a script contains a constant and an OP_CHECKSIG opcode, the constant is returned as it is
     * assumed to be a direct pay-to-key scriptPubKey (output) and the first constant is the public key.
     *
     * @throws ScriptException if the script is none of the named forms.
     */
    getPubKey(): Uint8Array {
        if (this.chunks.length < 2) {
            throw new ScriptException("Script not of right size, expecting 2 but got " + this.chunks.length);
        }
        const chunk0 = this.chunks[0];
        const chunk0data = chunk0.data;
        const chunk1 = this.chunks[1];
        const chunk1data = chunk1.data;
        if (chunk0data != null && chunk0data.length > 2 && chunk1data != null && chunk1data.length > 2) {
            // If we have two large constants assume the input to a pay-to-address output.
            return chunk1data;
        } else if (chunk1.equalsOpCode(OP_CHECKSIG) && chunk0data != null && chunk0data.length > 2) {
            // A large constant followed by an OP_CHECKSIG is the key.
            return chunk0data;
        } else {
            throw new ScriptException("Script did not match expected form: " + this.toString());
        }
    }

    /**
     * Retrieves the sender public key from a LOCKTIMEVERIFY transaction
     * @return
     * @throws ScriptException
     */
    getCLTVPaymentChannelSenderPubKey(): Uint8Array {
        if (!this.isSentToCLTVPaymentChannel()) {
            throw new ScriptException("Script not a standard CHECKLOCKTIMVERIFY transaction: " + this.toString());
        }
        if (!this.chunks[8].data) throw new ScriptException("No data in chunk 8 for CLTV sender pubkey");
        return this.chunks[8].data;
    }

    /**
     * Retrieves the recipient public key from a LOCKTIMEVERIFY transaction
     * @return
     * @throws ScriptException
     */
    getCLTVPaymentChannelRecipientPubKey(): Uint8Array {
        if (!this.isSentToCLTVPaymentChannel()) {
            throw new ScriptException("Script not a standard CHECKLOCKTIMVERIFY transaction: " + this.toString());
        }
        if (!this.chunks[1].data) throw new ScriptException("No data in chunk 1 for CLTV recipient pubkey");
        return this.chunks[1].data;
    }

    getCLTVPaymentChannelExpiry(): BigInteger {
        if (!this.isSentToCLTVPaymentChannel()) {
            throw new ScriptException("Script not a standard CHECKLOCKTIMEVERIFY transaction: " + this.toString());
        }
        if (!this.chunks[4].data) throw new ScriptException("No data in chunk 4 for CLTV expiry");
        return Script.castToBigInteger(this.chunks[4].data, 5);
    }

    /**
     * For 2-element [input] scripts assumes that the paid-to-address can be derived from the public key.
     * The concept of a "from address" isn't well defined in Bitcoin and you should not assume the sender of a
     * transaction can actually receive coins on it. This method may be removed in future.
     */
    getFromAddress(params: NetworkParameters): Address {
        // Use Address constructor with correct arguments
        return new Address(params, params.getAddressHeader(), Buffer.from(this.getPubKeyHash()));
    }

    /**
     * Gets the destination address from this script, if it's in the required form (see getPubKey).
     */
    getToAddress(params: NetworkParameters): Address;
    getToAddress(params: NetworkParameters, forcePayToPubKey: boolean): Address;
    getToAddress(params: NetworkParameters, forcePayToPubKey: boolean = true): Address {
        if (this.isSentToAddress()) {
            // Use the correct Address constructor: (params, version, hash160)
            // Assuming params.p2pkhVersion exists; adjust as needed for your codebase
            return new Address(params, params.getAddressHeader(), Buffer.from(this.getPubKeyHash()));
        } else if (this.isPayToScriptHash()) {
            // For P2SH, use the correct Address constructor with the P2SH version
            // Assuming params.p2shVersion exists; adjust as needed for your codebase
            return new Address(params, params.getP2SHHeader(), Buffer.from(this.getPubKeyHash()));
        } else if (forcePayToPubKey && this.isSentToRawPubKey()) {
            // Use ECKey.fromPublic instead of fromPublicOnly
            return ECKey.fromPublic(this.getPubKey()).toAddress(params);
        } else {
            throw new ScriptException("Cannot cast this script to a pay-to-address type");
        }
    }

    ////////////////////// Interface for writing scripts from scratch ////////////////////////////////

    /**
     * Writes out the given byte buffer to the output stream with the correct opcode prefix
     * To write an integer call writeBytes(out, Utils.reverseBytes(Utils.encodeMPI(val, false)));
     */
    static writeBytes(os: DataOutputStream, buf: Uint8Array): void {
        if (buf.length < OP_PUSHDATA1) {
            os.writeByte(buf.length);
            os.write(Buffer.from(buf));
        } else if (buf.length < 256) {
            os.writeByte(OP_PUSHDATA1);
            os.writeByte(buf.length);
            os.write(Buffer.from(buf));
        } else if (buf.length < 65536) {
            os.writeByte(OP_PUSHDATA2);
            os.writeByte(buf.length & 0xFF);
            os.writeByte((buf.length >> 8) & 0xFF);
            os.write(Buffer.from(buf));
        } else {
            throw new Error("Unimplemented: Data push larger than 65535 bytes");
        }
    }

    /** Creates a program that requires at least N of the given keys to sign, using OP_CHECKMULTISIG. */
    static createMultiSigOutputScript(threshold: number, pubkeys: ECKey[]): Script { // Changed return type to Script
        if (threshold <= 0) throw new Error("threshold must be greater than 0");
        if (threshold > pubkeys.length) throw new Error("threshold cannot be greater than number of pubkeys");
        if (pubkeys.length > 16) throw new Error("Too many pubkeys, max 16");

        // if (pubkeys.length > 3) { // This is a warning in Java, can be console.warn in TS
        //     Script.log.warn(`Creating a multi-signature output that is non-standard: ${pubkeys.length} pubkeys, should be <= 3`);
        // }

        const dos = new DataOutputStream();
        Script.writeBytes(dos, new Uint8Array([Script.encodeToOpN(threshold)]));
        for (const key of pubkeys) {
            Script.writeBytes(dos, key.getPubKey());
        }
        Script.writeBytes(dos, new Uint8Array([Script.encodeToOpN(pubkeys.length)]));
        dos.writeByte(OP_CHECKMULTISIG);
        return new Script(dos.toByteArray()); // Return a Script object
    }

    static createInputScript(signature: Uint8Array, pubkey: Uint8Array): Script; // Changed return type to Script
    static createInputScript(signature: Uint8Array): Script; // Changed return type to Script
    static createInputScript(param1: Uint8Array, param2?: Uint8Array): Script { // Changed return type to Script
        const bits = new DataOutputStream();
        if (param2 !== undefined) {
            // createInputScript(byte[] signature, byte[] pubkey)
            Script.writeBytes(bits, param1); // signature
            Script.writeBytes(bits, param2); // pubkey
        } else {
            // createInputScript(byte[] signature)
            Script.writeBytes(bits, param1); // signature
        }
        return new Script(bits.toByteArray()); // Return a Script object
    }

    /**
     * Creates an incomplete scriptSig that, once filled with signatures, can redeem output containing this scriptPubKey.
     * Instead of the signatures resulting script has OP_0.
     * Having incomplete input script allows to pass around partially signed tx.
     * It is expected that this program later on will be updated with proper signatures.
     */
    createEmptyInputScript(key: ECKey | null, redeemScript: Script | null): Script {
        if (this.isSentToAddress()) {
            if (key == null) throw new Error("Key required to create pay-to-address input script");
            return Script.createInputScript(new Uint8Array(), key.getPubKey()); // Dummy signature
        } else if (this.isSentToRawPubKey()) {
            return Script.createInputScript(new Uint8Array()); // Dummy signature
        } else if (this.isPayToScriptHash()) {
            if (redeemScript == null) throw new Error("Redeem script required to create P2SH input script");
            // This needs ScriptBuilder.createP2SHMultiSigInputScript, which is not yet translated.
            // For now, return a dummy script.
            return new Script(new Uint8Array()); // Placeholder
        } else {
            throw new ScriptException("Do not understand script type: " + this.toString());
        }
    }

    /**
     * Returns a copy of the given scriptSig with the signature inserted in the given position.
     */
    getScriptSigWithSignature(scriptSig: Script, sigBytes: Uint8Array, index: number): Script {
        let sigsPrefixCount = 0;
        let sigsSuffixCount = 0;
        if (this.isPayToScriptHash()) {
            sigsPrefixCount = 1; // OP_0 <sig>* <redeemScript>
            sigsSuffixCount = 1;
        } else if (this.isSentToMultiSig()) {
            sigsPrefixCount = 1; // OP_0 <sig>*
        } else if (this.isSentToAddress()) {
            sigsSuffixCount = 1; // <sig> <pubkey>
        }
        // This needs ScriptBuilder.updateScriptWithSignature, which is not yet translated.
        // For now, return a dummy script.
        return new Script(new Uint8Array()); // Placeholder
    }


    /**
     * Returns the index where a signature by the key should be inserted.  Only applicable to
     * a P2SH scriptSig.
     */
    getSigInsertionIndex(hash: Sha256Hash, signingKey: ECKey): number {
        // Iterate over existing signatures, skipping the initial OP_0, the final redeem script
        // and any placeholder OP_0 sigs.
        const existingChunks = this.chunks.slice(1, this.chunks.length - 1);
        const redeemScriptChunk = this.chunks[this.chunks.length - 1];
        if (!redeemScriptChunk.data) throw new Error("Redeem script chunk has no data");
        const redeemScript = new Script(redeemScriptChunk.data);

        let sigCount = 0;
        const myIndex = redeemScript.findKeyInRedeem(signingKey);
        for (const chunk of existingChunks) {
            if (chunk.opcode === OP_0) {
                // OP_0, skip
            } else {
                if (!chunk.data) throw new Error("Chunk has no data");
                if (myIndex < redeemScript.findSigInRedeem(chunk.data, hash)) {
                    return sigCount;
                }
                sigCount++;
            }
        }
        return sigCount;
    }

    private findKeyInRedeem(key: ECKey): number {
        if (!this.chunks[0].isOpCode()) throw new Error("P2SH scriptSig expected to start with opcode"); // P2SH scriptSig
        const numKeys = Script.decodeFromOpN(this.chunks[this.chunks.length - 2].opcode);
        for (let i = 0 ; i < numKeys ; i++) {
            if (this.chunks[1 + i].data && Utils.arraysEqual(this.chunks[1 + i].data!, key.getPubKey())) {
                return i;
            }
        }

        throw new Error(`Could not find matching key ${key.toString()} in script ${this.toString()}`);
    }

    /**
     * Returns a list of the keys required by this script, assuming a multi-sig script.
     *
     * @throws ScriptException if the script type is not understood or is pay to address or is P2SH (run this method on the "Redeem script" instead).
     */
    getPubKeys(): ECKey[] {
        if (!this.isSentToMultiSig()) {
            throw new ScriptException("Only usable for multisig scripts.");
        }

        const result: ECKey[] = [];
        const numKeys = Script.decodeFromOpN(this.chunks[this.chunks.length - 2].opcode);
        for (let i = 0 ; i < numKeys ; i++) {
            if (!this.chunks[1 + i].data) throw new Error("Pubkey chunk has no data");
            result.push(ECKey.fromPublic(this.chunks[1 + i].data!));
        }
        return result;
    }

    private findSigInRedeem(signatureBytes: Uint8Array, hash: Sha256Hash): number {
        if (!this.chunks[0].isOpCode()) throw new Error("P2SH scriptSig expected to start with opcode"); // P2SH scriptSig
        const numKeys = Script.decodeFromOpN(this.chunks[this.chunks.length - 2].opcode);
        const signature = TransactionSignature.decodeFromBitcoin(signatureBytes, true, false); // TransactionSignature extends ECDSASignature

        // Import the correct ECDSASignature class from core
        const { ECDSASignature } = require('../core/ECDSASignature');
        const sigForVerify = new ECDSASignature(signature.r, signature.s);

        for (let i = 0 ; i < numKeys ; i++) {
            if (!this.chunks[i + 1].data) throw new Error("Pubkey chunk has no data");
            // Use ECKey.fromPublic to create a key and call verify
            const pubKey = ECKey.fromPublic(this.chunks[i + 1].data!);
            // Pass the correct ECDSASignature instance
            if (pubKey.verify(hash.getBytes(), sigForVerify)) {
                return i;
            }
        }

        throw new Error(`Could not find matching key for signature on ${hash.toString()} sig ${Utils.HEX.encode(signatureBytes)}`);
    }



    ////////////////////// Interface used during verification of transactions/blocks ////////////////////////////////

    private static countSigOps(chunks: ScriptChunk[], accurate: boolean): number {
        let sigOps = 0;
        let lastOpCode = OP_INVALIDOPCODE;
        for (const chunk of chunks) {
            if (chunk.isOpCode()) {
                switch (chunk.opcode) {
                case OP_CHECKSIG:
                case OP_CHECKSIGVERIFY:
                    sigOps++;
                    break;
                case OP_CHECKMULTISIG:
                case OP_CHECKMULTISIGVERIFY:
                    if (accurate && lastOpCode >= OP_1 && lastOpCode <= OP_16) {
                        sigOps += Script.decodeFromOpN(lastOpCode);
                    } else {
                        sigOps += 20;
                    }
                    break;
                default:
                    break;
                }
                lastOpCode = chunk.opcode;
            }
        }
        return sigOps;
    }

    static decodeFromOpN(opcode: number): number {
        if (!((opcode === OP_0 || opcode === OP_1NEGATE) || (opcode >= OP_1 && opcode <= OP_16))) {
            throw new Error("decodeFromOpN called on non OP_N opcode");
        }
        if (opcode === OP_0) {
            return 0;
        } else if (opcode === OP_1NEGATE) {
            return -1;
        } else {
            return opcode + 1 - OP_1;
        }
    }

    static encodeToOpN(value: number): number {
        if (value < -1 || value > 16) {
            throw new Error(`encodeToOpN called for ${value} which we cannot encode in an opcode.`);
        }
        if (value === 0) {
            return OP_0;
        } else if (value === -1) {
            return OP_1NEGATE;
        } else {
            return value - 1 + OP_1;
        }
    }

    /**
     * Gets the count of regular SigOps in the script program (counting multisig ops as 20)
     */
    static getSigOpCount(program: Uint8Array): number {
        const script = new Script(program);
        return Script.countSigOps(script.chunks, false);
    }

    /**
     * Gets the count of P2SH Sig Ops in the Script scriptSig
     */
    static getP2SHSigOpCount(scriptSig: Uint8Array): number {
        const script = new Script(scriptSig);
        for (let i = script.chunks.length - 1; i >= 0; i--) {
            if (!script.chunks[i].isOpCode()) {
                const subScript = new Script(script.chunks[i].data!);
                return Script.countSigOps(subScript.chunks, true);
            }
        }
        return 0;
    }

    /**
     * Returns number of signatures required to satisfy this script.
     */
    getNumberOfSignaturesRequiredToSpend(): number {
        if (this.isSentToMultiSig()) {
            // for N of M CHECKMULTISIG script we will need N signatures to spend
            const nChunk = this.chunks[0];
            return Script.decodeFromOpN(nChunk.opcode);
        } else if (this.isSentToAddress() || this.isSentToRawPubKey()) {
            // pay-to-address and pay-to-pubkey require single sig
            return 1;
        } else if (this.isPayToScriptHash()) {
            throw new Error("For P2SH number of signatures depends on redeem script");
        } else {
            throw new Error("Unsupported script type");
        }
    }

    /**
     * Returns number of bytes required to spend this script. It accepts optional ECKey and redeemScript that may
     * be required for certain types of script to estimate target size.
     */
    getNumberOfBytesRequiredToSpend(pubKey: ECKey | null, redeemScript: Script | null): number {
        if (this.isPayToScriptHash()) {
            // scriptSig: <sig> [sig] [sig...] <redeemscript>
            if (redeemScript == null) throw new Error("P2SH script requires redeemScript to be spent");
            return redeemScript.getNumberOfSignaturesRequiredToSpend() * Script.SIG_SIZE + redeemScript.getProgram().length;
        } else if (this.isSentToMultiSig()) {
            // scriptSig: OP_0 <sig> [sig] [sig...]
            return this.getNumberOfSignaturesRequiredToSpend() * Script.SIG_SIZE + 1;
        } else if (this.isSentToRawPubKey()) {
            // scriptSig: <sig>
            return Script.SIG_SIZE;
        } else if (this.isSentToAddress()) {
            // scriptSig: <sig> <pubkey>
            const uncompressedPubKeySize = 65;
            return Script.SIG_SIZE + (pubKey != null ? pubKey.getPubKey().length : uncompressedPubKeySize);
        } else {
            throw new Error("Unsupported script type");
        }
    }

    /**
     * <p>Whether or not this is a scriptPubKey representing a pay-to-script-hash output. In such outputs, the logic that
     * controls reclamation is not actually in the output at all. Instead there's just a hash, and it's up to the
     * spending input to provide a program matching that hash. This rule is "soft enforced" by the network as it does
     * not exist in Bitcoin Core. It means blocks containing P2SH transactions that don't match
     * correctly are considered valid, but won't be mined upon, so they'll be rapidly re-orgd out of the chain. This
     * logic is defined by <a href="https://github.com/bitcoin/bips/blob/master/bip-0016.mediawiki">BIP 16</a>.</p>
     *
     * <p>bitcoinj does not support creation of P2SH transactions today. The goal of P2SH is to allow short addresses
     * even for complex scripts (eg, multi-sig outputs) so they are convenient to work with in things like QRcodes or
     * with copy/paste, and also to minimize the size of the unspent output set (which improves performance of the
     * Bitcoin system).</p>
     */
    isPayToScriptHash(): boolean {
        // We have to check against the serialized form because BIP16 defines a P2SH output using an exact byte
        // template, not the logical program structure. Thus you can have two programs that look identical when
        // printed out but one is a P2SH script and the other isn't! :(
        const program = this.getProgram();
        return program.length === 23 &&
               (program[0] & 0xff) === OP_HASH160 &&
               (program[1] & 0xff) === 0x14 &&
               (program[22] & 0xff) === OP_EQUAL;
    }

    /**
     * Returns whether this script matches the format used for multisig outputs: [n] [keys...] [m] CHECKMULTISIG
     */
    isSentToMultiSig(): boolean {
        if (this.chunks.length < 4) return false;
        const chunk = this.chunks[this.chunks.length - 1];
        // Must end in OP_CHECKMULTISIG[VERIFY].
        if (!chunk.isOpCode()) return false;
        if (!(chunk.equalsOpCode(OP_CHECKMULTISIG) || chunk.equalsOpCode(OP_CHECKMULTISIGVERIFY))) return false;
        try {
            // Second to last chunk must be an OP_N opcode and there should be that many data chunks (keys).
            const m = this.chunks[this.chunks.length - 2];
            if (!m.isOpCode()) return false;
            const numKeys = Script.decodeFromOpN(m.opcode);
            if (numKeys < 1 || this.chunks.length !== 3 + numKeys) return false;
            for (let i = 1; i < this.chunks.length - 2; i++) {
                if (this.chunks[i].isOpCode()) return false;
            }
            // First chunk must be an OP_N opcode too.
            if (Script.decodeFromOpN(this.chunks[0].opcode) < 1) return false;
        } catch (e) { // thrown by decodeFromOpN()
            return false;   // Not an OP_N opcode.
        }
        return true;
    }

    isSentToCLTVPaymentChannel(): boolean {
        if (this.chunks.length !== 10) return false;
        // Check that opcodes match the pre-determined format.
        if (!this.chunks[0].equalsOpCode(OP_IF)) return false;
        // chunk[1] = recipient pubkey
        if (!this.chunks[2].equalsOpCode(OP_CHECKSIGVERIFY)) return false;
        if (!this.chunks[3].equalsOpCode(OP_ELSE)) return false;
        // chunk[4] = locktime
        if (!this.chunks[5].equalsOpCode(OP_CHECKLOCKTIMEVERIFY)) return false;
        if (!this.chunks[6].equalsOpCode(OP_DROP)) return false;
        if (!this.chunks[7].equalsOpCode(OP_ENDIF)) return false;
        // chunk[8] = sender pubkey
        if (!this.chunks[9].equalsOpCode(OP_CHECKSIG)) return false;
        return true;
    }

    private static equalsRange(a: Uint8Array, start: number, b: Uint8Array): boolean {
        if (start + b.length > a.length) {
            return false;
        }
        for (let i = 0; i < b.length; i++) {
            if (a[i + start] !== b[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the script bytes of inputScript with all instances of the specified script object removed
     */
    static removeAllInstancesOf(inputScript: Uint8Array, chunkToRemove: Uint8Array): Uint8Array {
        const bos = new UnsafeByteArrayOutputStream(inputScript.length);
        let cursor = 0;
        while (cursor < inputScript.length) {
            const skip = Script.equalsRange(inputScript, cursor, chunkToRemove);

            const opcode = inputScript[cursor++] & 0xFF;
            let additionalBytes = 0;
            if (opcode >= 0 && opcode < OP_PUSHDATA1) {
                additionalBytes = opcode;
            } else if (opcode === OP_PUSHDATA1) {
                additionalBytes = (0xFF & inputScript[cursor]) + 1;
            } else if (opcode === OP_PUSHDATA2) {
                additionalBytes = ((0xFF & inputScript[cursor]) |
                                  ((0xFF & inputScript[cursor + 1]) << 8)) + 2;
            } else if (opcode === OP_PUSHDATA4) {
                additionalBytes = ((0xFF & inputScript[cursor]) |
                                  ((0xFF & inputScript[cursor + 1]) << 8) |
                                  ((0xFF & inputScript[cursor + 2]) << 16) |
                                  ((0xFF & inputScript[cursor + 3]) << 24)) + 4;
            }
            if (!skip) {
                bos.write(opcode);
                bos.write(Buffer.from(inputScript.slice(cursor, cursor + additionalBytes)));
            }
            cursor += additionalBytes;
        }
        return bos.toByteArray();
    }

    /**
     * Returns the script bytes of inputScript with all instances of the given op code removed
     */
    static removeAllInstancesOfOp(inputScript: Uint8Array, opCode: number): Uint8Array {
        return Script.removeAllInstancesOf(inputScript, new Uint8Array([(opCode as number)]));
    }

    ////////////////////// Script verification and helpers ////////////////////////////////

    private static castToBool(data: Uint8Array): boolean {
        for (let i = 0; i < data.length; i++) {
            // "Can be negative zero" - Bitcoin Core (see OpenSSL's BN_bn2mpi)
            if (data[i] !== 0) {
                return !(i === data.length - 1 && (data[i] & 0xFF) === 0x80);
            }
        }
        return false;
    }

    /**
     * Cast a script chunk to a BigInteger.
     *
     * @see #castToBigInteger(byte[], int) for values with different maximum
     * sizes.
     * @throws ScriptException if the chunk is longer than 4 bytes.
     */
    private static castToBigInteger(chunk: Uint8Array): BigInteger;
    private static castToBigInteger(chunk: Uint8Array, maxLength: number): BigInteger;
    private static castToBigInteger(chunk: Uint8Array, maxLength?: number): BigInteger {
        if (maxLength !== undefined && chunk.length > maxLength) {
            throw new ScriptException(`Script attempted to use an integer larger than ${maxLength} bytes`);
        }
        if (maxLength === undefined && chunk.length > 4) {
            throw new ScriptException("Script attempted to use an integer larger than 4 bytes");
        }
        return Utils.decodeMPI(Utils.reverseBytes(chunk), false);
    }

    isOpReturn(): boolean {
        return this.chunks.length > 0 && this.chunks[0].equalsOpCode(OP_RETURN);
    }

    /**
     * Exposes the script interpreter. Normally you should not use this directly, instead use
     * {@link net.bigtangle.core.TransactionInput#verify(net.bigtangle.core.TransactionOutput)} or
     * {@link net.bigtangle.script.Script#correctlySpends(net.bigtangle.core.Transaction, long, Script)}. This method
     * is useful if you need more precise control or access to the final state of the stack. This interface is very
     * likely to change in future.
     */
    static executeScript(txContainingThis: Transaction | null, index: number,
                         script: Script, stack: Uint8Array[], verifyFlags: Set<Script.VerifyFlag>): void {
        let opCount = 0;
        let lastCodeSepLocation = 0;

        const altstack: Uint8Array[] = [];
        const ifStack: boolean[] = [];

        for (const chunk of script.chunks) {
            const shouldExecute = !ifStack.includes(false);

            if (chunk.opcode === OP_0) {
                if (!shouldExecute) {
                    continue;
                }
                stack.push(new Uint8Array());
            } else if (!chunk.isOpCode()) {
                if ((chunk.data?.length || 0) > Script.MAX_SCRIPT_ELEMENT_SIZE) {
                    throw new ScriptException("Attempted to push a data string larger than 520 bytes");
                }
                if (!shouldExecute) {
                    continue;
                }
                stack.push(chunk.data!);
            } else {
                const opcode = chunk.opcode;
                if (opcode > OP_16) {
                    opCount++;
                    if (opCount > 201) {
                        throw new ScriptException("More script operations than is allowed");
                    }
                }

                if (opcode === OP_VERIF || opcode === OP_VERNOTIF) {
                    throw new ScriptException("Script included OP_VERIF or OP_VERNOTIF");
                }

                if (opcode === OP_CAT || opcode === OP_SUBSTR || opcode === OP_LEFT || opcode === OP_RIGHT ||
                    opcode === OP_INVERT || opcode === OP_AND || opcode === OP_OR || opcode === OP_XOR ||
                    opcode === OP_2MUL || opcode === OP_2DIV || opcode === OP_MUL || opcode === OP_DIV ||
                    opcode === OP_MOD || opcode === OP_LSHIFT || opcode === OP_RSHIFT) {
                    throw new ScriptException("Script included a disabled Script Op.");
                }

                switch (opcode) {
                case OP_IF:
                    if (!shouldExecute) {
                        ifStack.push(false);
                        continue;
                    }
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_IF on an empty stack");
                    }
                    ifStack.push(Script.castToBool(stack.pop()!));
                    continue;
                case OP_NOTIF:
                    if (!shouldExecute) {
                        ifStack.push(false);
                        continue;
                    }
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_NOTIF on an empty stack");
                    }
                    ifStack.push(!Script.castToBool(stack.pop()!));
                    continue;
                case OP_ELSE:
                    if (ifStack.length === 0) {
                        throw new ScriptException("Attempted OP_ELSE without OP_IF/NOTIF");
                    }
                    ifStack.push(!ifStack.pop()!);
                    continue;
                case OP_ENDIF:
                    if (ifStack.length === 0) {
                        throw new ScriptException("Attempted OP_ENDIF without OP_IF/NOTIF");
                    }
                    ifStack.pop();
                    continue;
                }

                if (!shouldExecute) {
                    continue;
                }

                switch (opcode) {
                // OP_0 is no opcode
                case OP_1NEGATE:
                    stack.push(Utils.reverseBytes(Utils.encodeMPI(BigInteger.ONE.negate(), false)));
                    break;
                case OP_1:
                case OP_2:
                case OP_3:
                case OP_4:
                case OP_5:
                case OP_6:
                case OP_7:
                case OP_8:
                case OP_9:
                case OP_10:
                case OP_11:
                case OP_12:
                case OP_13:
                case OP_14:
                case OP_15:
                case OP_16:
                    stack.push(Utils.reverseBytes(Utils.encodeMPI(new BigInteger(String(Script.decodeFromOpN(opcode))), false)));
                    break;
                case OP_NOP:
                    break;
                case OP_VERIFY:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_VERIFY on an empty stack");
                    }
                    if (!Script.castToBool(stack.pop()!)) {
                        throw new ScriptException("OP_VERIFY failed");
                    }
                    break;
                case OP_RETURN:
                    throw new ScriptException("Script called OP_RETURN");
                case OP_TOALTSTACK:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_TOALTSTACK on an empty stack");
                    }
                    altstack.push(stack.pop()!);
                    break;
                case OP_FROMALTSTACK:
                    if (altstack.length < 1) {
                        throw new ScriptException("Attempted OP_FROMALTSTACK on an empty altstack");
                    }
                    stack.push(altstack.pop()!);
                    break;
                case OP_2DROP:
                    if (stack.length < 2) {
                        throw new ScriptException("Attempted OP_2DROP on a stack with size < 2");
                    }
                    stack.pop();
                    stack.pop();
                    break;
                case OP_2DUP:
                    if (stack.length < 2) {
                        throw new ScriptException("Attempted OP_2DUP on a stack with size < 2");
                    }
                    const OP2DUPtmpChunk2 = stack[stack.length - 1];
                    stack.push(stack[stack.length - 2]);
                    stack.push(OP2DUPtmpChunk2);
                    break;
                case OP_3DUP:
                    if (stack.length < 3) {
                        throw new ScriptException("Attempted OP_3DUP on a stack with size < 3");
                    }
                    const OP3DUPtmpChunk3 = stack[stack.length - 1];
                    const OP3DUPtmpChunk2 = stack[stack.length - 2];
                    stack.push(stack[stack.length - 3]);
                    stack.push(OP3DUPtmpChunk2);
                    stack.push(OP3DUPtmpChunk3);
                    break;
                case OP_2OVER:
                    if (stack.length < 4) {
                        throw new ScriptException("Attempted OP_2OVER on a stack with size < 4");
                    }
                    const OP2OVERtmpChunk2 = stack[stack.length - 3];
                    stack.push(stack[stack.length - 4]);
                    stack.push(OP2OVERtmpChunk2);
                    break;
                case OP_2ROT:
                    if (stack.length < 6) {
                        throw new ScriptException("Attempted OP_2ROT on a stack with size < 6");
                    }
                    const OP2ROTtmpChunk6 = stack.pop()!;
                    const OP2ROTtmpChunk5 = stack.pop()!;
                    const OP2ROTtmpChunk4 = stack.pop()!;
                    const OP2ROTtmpChunk3 = stack.pop()!;
                    const OP2ROTtmpChunk2 = stack.pop()!;
                    const OP2ROTtmpChunk1 = stack.pop()!;
                    stack.push(OP2ROTtmpChunk3);
                    stack.push(OP2ROTtmpChunk4);
                    stack.push(OP2ROTtmpChunk5);
                    stack.push(OP2ROTtmpChunk6);
                    stack.push(OP2ROTtmpChunk1);
                    stack.push(OP2ROTtmpChunk2);
                    break;
                case OP_2SWAP:
                    if (stack.length < 4) {
                        throw new ScriptException("Attempted OP_2SWAP on a stack with size < 4");
                    }
                    const OP2SWAPtmpChunk4 = stack.pop()!;
                    const OP2SWAPtmpChunk3 = stack.pop()!;
                    const OP2SWAPtmpChunk2 = stack.pop()!;
                    const OP2SWAPtmpChunk1 = stack.pop()!;
                    stack.push(OP2SWAPtmpChunk3);
                    stack.push(OP2SWAPtmpChunk4);
                    stack.push(OP2SWAPtmpChunk1);
                    stack.push(OP2SWAPtmpChunk2);
                    break;
                case OP_IFDUP:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_IFDUP on an empty stack");
                    }
                    if (Script.castToBool(stack[stack.length - 1])) {
                        stack.push(stack[stack.length - 1]);
                    }
                    break;
                case OP_DEPTH:
                    stack.push(Utils.reverseBytes(Utils.encodeMPI(new BigInteger(String(stack.length)), false)));
                    break;
                case OP_DROP:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_DROP on an empty stack");
                    }
                    stack.pop();
                    break;
                case OP_DUP:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_DUP on an empty stack");
                    }
                    stack.push(stack[stack.length - 1]);
                    break;
                case OP_NIP:
                    if (stack.length < 2) {
                        throw new ScriptException("Attempted OP_NIP on a stack with size < 2");
                    }
                    const OPNIPtmpChunk = stack.pop()!;
                    stack.pop();
                    stack.push(OPNIPtmpChunk);
                    break;
                case OP_OVER:
                    if (stack.length < 2) {
                        throw new ScriptException("Attempted OP_OVER on a stack with size < 2");
                    }
                    stack.push(stack[stack.length - 2]);
                    break;
                case OP_PICK:
                case OP_ROLL:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_PICK/OP_ROLL on an empty stack");
                    }
                    const val = Script.castToBigInteger(stack.pop()!).intValue();
                    if (val < 0 || val >= stack.length) {
                        throw new ScriptException("OP_PICK/OP_ROLL attempted to get data deeper than stack size");
                    }
                    const OPROLLtmpChunk = stack.splice(stack.length - 1 - val, 1)[0];
                    stack.push(OPROLLtmpChunk);
                    break;
                case OP_ROT:
                    if (stack.length < 3) {
                        throw new ScriptException("Attempted OP_ROT on a stack with size < 3");
                    }
                    const OPROTtmpChunk3 = stack.pop()!;
                    const OPROTtmpChunk2 = stack.pop()!;
                    const OPROTtmpChunk1 = stack.pop()!;
                    stack.push(OPROTtmpChunk2);
                    stack.push(OPROTtmpChunk3);
                    stack.push(OPROTtmpChunk1);
                    break;
                case OP_SWAP:
                case OP_TUCK:
                    if (stack.length < 2) {
                        throw new ScriptException("Attempted OP_SWAP on a stack with size < 2");
                    }
                    const OPSWAPtmpChunk2 = stack.pop()!;
                    const OPSWAPtmpChunk1 = stack.pop()!;
                    stack.push(OPSWAPtmpChunk2);
                    stack.push(OPSWAPtmpChunk1);
                    if (opcode === OP_TUCK) {
                        stack.push(OPSWAPtmpChunk2);
                    }
                    break;
                case OP_CAT:
                case OP_SUBSTR:
                case OP_LEFT:
                case OP_RIGHT:
                    throw new ScriptException("Attempted to use disabled Script Op.");
                case OP_SIZE:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_SIZE on an empty stack");
                    }
                    stack.push(Utils.reverseBytes(Utils.encodeMPI(new BigInteger(String(stack[stack.length - 1].length)), false)));
                    break;
                case OP_INVERT:
                case OP_AND:
                case OP_OR:
                case OP_XOR:
                    throw new ScriptException("Attempted to use disabled Script Op.");
                case OP_EQUAL:
                    if (stack.length < 2) {
                        throw new ScriptException("Attempted OP_EQUAL on a stack with size < 2");
                    }
                    stack.push(Utils.arraysEqual(stack.pop()!, stack.pop()!) ? new Uint8Array([1]) : new Uint8Array());
                    break;
                case OP_EQUALVERIFY:
                    if (stack.length < 2) {
                        throw new ScriptException("Attempted OP_EQUALVERIFY on a stack with size < 2");
                    }
                    if (!Utils.arraysEqual(stack.pop()!, stack.pop()!)) {
                        throw new ScriptException("OP_EQUALVERIFY: non-equal data");
                    }
                    break;
                case OP_1ADD:
                case OP_1SUB:
                case OP_NEGATE:
                case OP_ABS:
                case OP_NOT:
                case OP_0NOTEQUAL:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted a numeric op on an empty stack");
                    }
                    let numericOPnum = Script.castToBigInteger(stack.pop()!);

                    switch (opcode) {
                    case OP_1ADD:
                        numericOPnum = numericOPnum.add(BigInteger.ONE);
                        break;
                    case OP_1SUB:
                        numericOPnum = numericOPnum.subtract(BigInteger.ONE);
                        break;
                    case OP_NEGATE:
                        numericOPnum = numericOPnum.negate();
                        break;
                    case OP_ABS:
                        if (numericOPnum.signum() < 0) {
                            numericOPnum = numericOPnum.negate();
                        }
                        break;
                    case OP_NOT:
                        if (numericOPnum.equals(BigInteger.ZERO)) {
                            numericOPnum = BigInteger.ONE;
                        } else {
                            numericOPnum = BigInteger.ZERO;
                        }
                        break;
                    case OP_0NOTEQUAL:
                        if (numericOPnum.equals(BigInteger.ZERO)) {
                            numericOPnum = BigInteger.ZERO;
                        } else {
                            numericOPnum = BigInteger.ONE;
                        }
                        break;
                    default:
                        throw new Error("Unreachable");
                    }

                    stack.push(Utils.reverseBytes(Utils.encodeMPI(numericOPnum, false)));
                    break;
                case OP_2MUL:
                case OP_2DIV:
                    throw new ScriptException("Attempted to use disabled Script Op.");
                case OP_ADD:
                case OP_SUB:
                case OP_BOOLAND:
                case OP_BOOLOR:
                case OP_NUMEQUAL:
                case OP_NUMNOTEQUAL:
                case OP_LESSTHAN:
                case OP_GREATERTHAN:
                case OP_LESSTHANOREQUAL:
                case OP_GREATERTHANOREQUAL:
                case OP_MIN:
                case OP_MAX:
                    if (stack.length < 2) {
                        throw new ScriptException("Attempted a numeric op on a stack with size < 2");
                    }
                    const numericOPnum2 = Script.castToBigInteger(stack.pop()!);
                    const numericOPnum1 = Script.castToBigInteger(stack.pop()!);

                    let numericOPresult: BigInteger;
                    switch (opcode) {
                    case OP_ADD:
                        numericOPresult = numericOPnum1.add(numericOPnum2);
                        break;
                    case OP_SUB:
                        numericOPresult = numericOPnum1.subtract(numericOPnum2);
                        break;
                    case OP_BOOLAND:
                        if (!numericOPnum1.equals(BigInteger.ZERO) && !numericOPnum2.equals(BigInteger.ZERO)) {
                            numericOPresult = BigInteger.ONE;
                        } else {
                            numericOPresult = BigInteger.ZERO;
                        }
                        break;
                    case OP_BOOLOR:
                        if (!numericOPnum1.equals(BigInteger.ZERO) || !numericOPnum2.equals(BigInteger.ZERO)) {
                            numericOPresult = BigInteger.ONE;
                        } else {
                            numericOPresult = BigInteger.ZERO;
                        }
                        break;
                    case OP_NUMEQUAL:
                        if (numericOPnum1.equals(numericOPnum2)) {
                            numericOPresult = BigInteger.ONE;
                        } else {
                            numericOPresult = BigInteger.ZERO;
                        }
                        break;
                    case OP_NUMNOTEQUAL:
                        if (!numericOPnum1.equals(numericOPnum2)) {
                            numericOPresult = BigInteger.ONE;
                        } else {
                            numericOPresult = BigInteger.ZERO;
                        }
                        break;
                    case OP_LESSTHAN:
                        if (numericOPnum1.compareTo(numericOPnum2) < 0) {
                            numericOPresult = BigInteger.ONE;
                        } else {
                            numericOPresult = BigInteger.ZERO;
                        }
                        break;
                    case OP_GREATERTHAN:
                        if (numericOPnum1.compareTo(numericOPnum2) > 0) {
                            numericOPresult = BigInteger.ONE;
                        } else {
                            numericOPresult = BigInteger.ZERO;
                        }
                        break;
                    case OP_LESSTHANOREQUAL:
                        if (numericOPnum1.compareTo(numericOPnum2) <= 0) {
                            numericOPresult = BigInteger.ONE;
                        } else {
                            numericOPresult = BigInteger.ZERO;
                        }
                        break;
                    case OP_GREATERTHANOREQUAL:
                        if (numericOPnum1.compareTo(numericOPnum2) >= 0) {
                            numericOPresult = BigInteger.ONE;
                        } else {
                            numericOPresult = BigInteger.ZERO;
                        }
                        break;
                    case OP_MIN:
                        if (numericOPnum1.compareTo(numericOPnum2) < 0) {
                            numericOPresult = numericOPnum1;
                        } else {
                            numericOPresult = numericOPnum2;
                        }
                        break;
                    case OP_MAX:
                        if (numericOPnum1.compareTo(numericOPnum2) > 0) {
                            numericOPresult = numericOPnum1;
                        } else {
                            numericOPresult = numericOPnum2;
                        }
                        break;
                    default:
                        throw new Error("Opcode switched at runtime?");
                    }

                    stack.push(Utils.reverseBytes(Utils.encodeMPI(numericOPresult, false)));
                    break;
                case OP_MUL:
                case OP_DIV:
                case OP_MOD:
                case OP_LSHIFT:
                case OP_RSHIFT:
                    throw new ScriptException("Attempted to use disabled Script Op.");
                case OP_NUMEQUALVERIFY:
                    if (stack.length < 2) {
                        throw new ScriptException("Attempted OP_NUMEQUALVERIFY on a stack with size < 2");
                    }
                    const OPNUMEQUALVERIFYnum2 = Script.castToBigInteger(stack.pop()!);
                    const OPNUMEQUALVERIFYnum1 = Script.castToBigInteger(stack.pop()!);

                    if (!OPNUMEQUALVERIFYnum1.equals(OPNUMEQUALVERIFYnum2)) {
                        throw new ScriptException("OP_NUMEQUALVERIFY failed");
                    }
                    break;
                case OP_WITHIN:
                    if (stack.length < 3) {
                        throw new ScriptException("Attempted OP_WITHIN on a stack with size < 3");
                    }
                    const OPWITHINnum3 = Script.castToBigInteger(stack.pop()!);
                    const OPWITHINnum2 = Script.castToBigInteger(stack.pop()!);
                    const OPWITHINnum1 = Script.castToBigInteger(stack.pop()!);
                    if (OPWITHINnum2.compareTo(OPWITHINnum1) <= 0 && OPWITHINnum1.compareTo(OPWITHINnum3) < 0) {
                        stack.push(Utils.reverseBytes(Utils.encodeMPI(BigInteger.ONE, false)));
                    } else {
                        stack.push(Utils.reverseBytes(Utils.encodeMPI(BigInteger.ZERO, false)));
                    }
                    break;
                case OP_RIPEMD160:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_RIPEMD160 on an empty stack");
                    }
                    // In a real implementation, use a crypto library for RIPEMD160
                    const dataToHashRIPEMD = stack.pop()!;
                    const ripmemdHash = new Uint8Array(20); // Dummy hash
                    for (let i = 0; i < 20; i++) ripmemdHash[i] = Math.floor(Math.random() * 256);
                    stack.push(ripmemdHash);
                    break;
                case OP_SHA1:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_SHA1 on an empty stack");
                    }
                    // In a real implementation, use a crypto library for SHA1
                    const dataToHashSHA1 = stack.pop()!;
                    const sha1Hash = new Uint8Array(20); // Dummy hash
                    for (let i = 0; i < 20; i++) sha1Hash[i] = Math.floor(Math.random() * 256);
                    stack.push(sha1Hash);
                    break;
                case OP_SHA256:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_SHA256 on an empty stack");
                    }
                    stack.push(Sha256Hash.hash(Buffer.from(stack.pop()!)).getBytes());
                    break;
                case OP_HASH160:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_HASH160 on an empty stack");
                    }
                    stack.push(Utils.sha256hash160(stack.pop()!));
                    break;
                case OP_HASH256:
                    if (stack.length < 1) {
                        throw new ScriptException("Attempted OP_SHA256 on an empty stack");
                    }
                    stack.push(Sha256Hash.hashTwice(Buffer.from(stack.pop()!)).getBytes());
                    break;
                case OP_CODESEPARATOR:
                    lastCodeSepLocation = chunk.getStartLocationInProgram() + 1;
                    break;
                case OP_CHECKSIG:
                case OP_CHECKSIGVERIFY:
                    if (txContainingThis == null) {
                        throw new Error("Script attempted signature check but no tx was provided");
                    }
                    Script.executeCheckSig(txContainingThis, index, script, stack, lastCodeSepLocation, opcode, verifyFlags);
                    break;
                case OP_CHECKMULTISIG:
                case OP_CHECKMULTISIGVERIFY:
                    if (txContainingThis == null) {
                        throw new Error("Script attempted signature check but no tx was provided");
                    }
                    opCount = Script.executeMultiSig(txContainingThis, index, script, stack, opCount, lastCodeSepLocation, opcode, verifyFlags);
                    break;
                case OP_CHECKLOCKTIMEVERIFY:
                    if (!verifyFlags.has(Script.VerifyFlag.CHECKLOCKTIMEVERIFY)) {
                        // not enabled; treat as a NOP2
                        if (verifyFlags.has(Script.VerifyFlag.DISCOURAGE_UPGRADABLE_NOPS)) {
                            throw new ScriptException("Script used a reserved opcode " + opcode);
                        }
                        break;
                    }
                    if (txContainingThis == null) {
                        throw new Error("Script attempted CHECKLOCKTIMEVERIFY but no tx was provided");
                    }
                    Script.executeCheckLockTimeVerify(txContainingThis, index, script, stack, lastCodeSepLocation, opcode, verifyFlags);
                    break;
                case OP_NOP1:
                case OP_NOP3:
                case OP_NOP4:
                case OP_NOP5:
                case OP_NOP6:
                case OP_NOP7:
                case OP_NOP8:
                case OP_NOP9:
                case OP_NOP10:
                    if (verifyFlags.has(Script.VerifyFlag.DISCOURAGE_UPGRADABLE_NOPS)) {
                        throw new ScriptException("Script used a reserved opcode " + opcode);
                    }
                    break;

                default:
                    throw new ScriptException("Script used a reserved opcode " + opcode);
                }
            }

            if (stack.length + altstack.length > 1000 || stack.length + altstack.length < 0) {
                throw new ScriptException("Stack size exceeded range");
            }
        }

        if (ifStack.length !== 0) {
            throw new ScriptException("OP_IF/OP_NOTIF without OP_ENDIF");
        }
    }

    // This is more or less a direct translation of the code in Bitcoin Core
    private static executeCheckLockTimeVerify(txContainingThis: Transaction, index: number, script: Script, stack: Uint8Array[],
                                        lastCodeSepLocation: number, opcode: number,
                                        verifyFlags: Set<Script.VerifyFlag>): void {
        if (stack.length < 1) {
            throw new ScriptException("Attempted OP_CHECKLOCKTIMEVERIFY on a stack with size < 1");
        }

        // Thus as a special case we tell CScriptNum to accept up
        // to 5-byte bignums to avoid year 2038 issue.
        const nLockTime = Script.castToBigInteger(stack[stack.length - 1], 5);

        if (nLockTime.compareTo(BigInteger.ZERO) < 0) {
            throw new ScriptException("Negative locktime");
        }

        // There are two kinds of nLockTime, need to ensure we're comparing apples-to-apples
        if (!(
            ((txContainingThis.getLockTime() <  Transaction.LOCKTIME_THRESHOLD) && (nLockTime.compareTo(new BigInteger(Transaction.LOCKTIME_THRESHOLD_BIG.toString()))) < 0) ||
            ((txContainingThis.getLockTime() >= Transaction.LOCKTIME_THRESHOLD) && (nLockTime.compareTo(new BigInteger(Transaction.LOCKTIME_THRESHOLD.toString()))) >= 0))
        ) {
            throw new ScriptException("Locktime requirement type mismatch");
        }

        // Now that we know we're comparing apples-to-apples, the
        // comparison is a simple numeric one.
        if (nLockTime.compareTo(new BigInteger(String(txContainingThis.getLockTime()))) > 0) {
            throw new ScriptException("Locktime requirement not satisfied");
        }

        // Finally the nLockTime feature can be disabled and thus
        // CHECKLOCKTIMEVERIFY bypassed if every txin has been
        // finalized by setting nSequence to maxint. The
        // transaction would be allowed into the blockchain, making
        // the opcode ineffective.
        //
        // Testing if this vin is not final is sufficient to
        // prevent this condition. Alternatively we could test all
        // inputs, but testing just this input minimizes the data
        // required to prove correct CHECKLOCKTIMEVERTY execution.
        if (!txContainingThis.getInput(index).hasSequence()) {
            throw new ScriptException("Transaction contains a final transaction input for a CHECKLOCKTIMEVERIFY script.");
        }
    }

    private static executeCheckSig(txContainingThis: Transaction, index: number, script: Script, stack: Uint8Array[],
                                        lastCodeSepLocation: number, opcode: number,
                                        verifyFlags: Set<Script.VerifyFlag>): void {
        const requireCanonical = verifyFlags.has(Script.VerifyFlag.STRICTENC) ||
                                 verifyFlags.has(Script.VerifyFlag.DERSIG) ||
                                 verifyFlags.has(Script.VerifyFlag.LOW_S);
        if (stack.length < 2) {
            throw new ScriptException("Attempted OP_CHECKSIG(VERIFY) on a stack with size < 2");
        }
        const pubKey = stack.pop()!;
        const sigBytes = stack.pop()!;

        const prog = script.getProgram();
        let connectedScript = prog.slice(lastCodeSepLocation, prog.length);

        const outStream = new DataOutputStream();
        Script.writeBytes(outStream, sigBytes);
        connectedScript = Script.removeAllInstancesOf(connectedScript, outStream.toByteArray());

        // TODO: Use int for indexes everywhere, we can't have that many inputs/outputs
        let sigValid = false;
        try {
            const sig = TransactionSignature.decodeFromBitcoin(sigBytes, requireCanonical,
                verifyFlags.has(Script.VerifyFlag.LOW_S));

            // TODO: Should check hash type is known
            const hash = txContainingThis.hashForSignature(index, connectedScript, sig.sighashFlags);
            // Convert to core ECDSASignature for verification
            const { ECDSASignature } = require('../core/ECDSASignature');
            const sigForVerify = new ECDSASignature(sig.r, sig.s);
            sigValid = ECKey.fromPublic(pubKey).verify(hash.getBytes(), sigForVerify);
        } catch (e: any) {
            // There is (at least) one exception that could be hit here (EOFException, if the sig is too short)
            // Because I can't verify there aren't more, we use a very generic Exception catch

            // This RuntimeException occurs when signing as we run partial/invalid scripts to see if they need more
            // signing work to be done inside LocalTransactionSigner.signInputs.
            if (!e.message.includes("Reached past end of ASN.1 stream")) {
                Script.log.warn("Signature checking failed!", e);
            }
        }

        if (opcode === OP_CHECKSIG) {
            stack.push(sigValid ? new Uint8Array([1]) : new Uint8Array());
        } else if (opcode === OP_CHECKSIGVERIFY) {
            if (!sigValid) {
                throw new ScriptException("Script failed OP_CHECKSIGVERIFY");
            }
        }
    }

    private static executeMultiSig(txContainingThis: Transaction, index: number, script: Script, stack: Uint8Array[],
                                       opCount: number, lastCodeSepLocation: number, opcode: number,
                                       verifyFlags: Set<Script.VerifyFlag>): number {
        const requireCanonical = verifyFlags.has(Script.VerifyFlag.STRICTENC) ||
                                 verifyFlags.has(Script.VerifyFlag.DERSIG) ||
                                 verifyFlags.has(Script.VerifyFlag.LOW_S);
        if (stack.length < 2) {
            throw new ScriptException("Attempted OP_CHECKMULTISIG(VERIFY) on a stack with size < 2");
        }
        const pubKeyCount = Script.castToBigInteger(stack.pop()!).intValue();
        if (pubKeyCount < 0 || pubKeyCount > 20) {
            throw new ScriptException("OP_CHECKMULTISIG(VERIFY) with pubkey count out of range");
        }
        opCount += pubKeyCount;
        if (opCount > 201) {
            throw new ScriptException("Total op count > 201 during OP_CHECKMULTISIG(VERIFY)");
        }
        if (stack.length < pubKeyCount + 1) {
            throw new ScriptException("Attempted OP_CHECKMULTISIG(VERIFY) on a stack with size < num_of_pubkeys + 2");
        }

        const pubkeys: Uint8Array[] = [];
        for (let i = 0; i < pubKeyCount; i++) {
            const pubKey = stack.pop()!;
            pubkeys.push(pubKey);
        }

        const sigCount = Script.castToBigInteger(stack.pop()!).intValue();
        if (sigCount < 0 || sigCount > pubKeyCount) {
            throw new ScriptException("OP_CHECKMULTISIG(VERIFY) with sig count out of range");
        }
        if (stack.length < sigCount + 1) {
            throw new ScriptException("Attempted OP_CHECKMULTISIG(VERIFY) on a stack with size < num_of_pubkeys + num_of_signatures + 3");
        }

        const sigs: Uint8Array[] = [];
        for (let i = 0; i < sigCount; i++) {
            const sig = stack.pop()!;
            sigs.push(sig);
        }

        const prog = script.getProgram();
        let connectedScript = prog.slice(lastCodeSepLocation, prog.length);

        for (const sig of sigs) {
            const outStream = new DataOutputStream();
            Script.writeBytes(outStream, sig);
            connectedScript = Script.removeAllInstancesOf(connectedScript, outStream.toByteArray());
        }

        let valid = true;
        while (sigs.length > 0) {
            const pubKey = pubkeys.shift()!; // Use shift to get from the beginning
            // We could reasonably move this out of the loop, but because signature verification is significantly
            // more expensive than hashing, its not a big deal.
            try {
                const sig = TransactionSignature.decodeFromBitcoin(sigs[0], requireCanonical, false);
                const hash = txContainingThis.hashForSignature(index, connectedScript, sig.sighashFlags);
                // Convert to core ECDSASignature for verification
                const { ECDSASignature } = require('../core/ECDSASignature');
                const sigForVerify = new ECDSASignature(sig.r, sig.s);
                if (ECKey.fromPublic(pubKey).verify(hash.getBytes(), sigForVerify)) {
                    sigs.shift(); // Remove the used signature
                }
            } catch (e: any) {
                // There is (at least) one exception that could be hit here (EOFException, if the sig is too short)
                // Because I can't verify there aren't more, we use a very generic Exception catch
            }

            if (sigs.length > pubkeys.length) {
                valid = false;
                break;
            }
        }

        // We uselessly remove a stack object to emulate a Bitcoin Core bug.
        const nullDummy = stack.pop()!;
        if (verifyFlags.has(Script.VerifyFlag.NULLDUMMY) && nullDummy.length > 0) {
            throw new ScriptException(`OP_CHECKMULTISIG(VERIFY) with non-null nulldummy: ${nullDummy}`);
        }

        if (opcode === OP_CHECKMULTISIG) {
            stack.push(valid ? new Uint8Array([1]) : new Uint8Array());
        } else if (opcode === OP_CHECKMULTISIGVERIFY) {
            if (!valid) {
                throw new ScriptException("Script failed OP_CHECKMULTISIGVERIFY");
            }
        }
        return opCount;
    }

    /**
     * Verifies that this script (interpreted as a scriptSig) correctly spends the given scriptPubKey.
     * @param txContainingThis The transaction in which this input scriptSig resides.
     *                         Accessing txContainingThis from another thread while this method runs results in undefined behavior.
     * @param scriptSigIndex The index in txContainingThis of the scriptSig (note: NOT the index of the scriptPubKey).
     * @param scriptPubKey The connected scriptPubKey containing the conditions needed to claim the value.
     * @param verifyFlags Each flag enables one validation rule. If in doubt, use {@link #correctlySpends(Transaction, long, Script)}
     *                    which sets all flags.
     */
    correctlySpends(txContainingThis: Transaction, scriptSigIndex: number, scriptPubKey: Script,
                                verifyFlags: Set<Script.VerifyFlag>): void {
        // Clone the transaction because executing the script involves editing it, and if we die, we'll leave
        // the tx half broken (also it's not so thread safe to work on it directly.
        try {
            // This needs BitcoinSerializer.makeTransaction, which is not yet translated.
            // For now, use a dummy clone.
            // txContainingThis = {} as Transaction; // Placeholder
            txContainingThis = txContainingThis.getParams().getDefaultSerializer().makeTransaction(txContainingThis.bitcoinSerialize());
        } catch (e: any) {
            throw new Error(e);   // Should not happen unless we were given a totally broken transaction.
        }
       
        if (this.getProgram().length > 10000 || scriptPubKey.getProgram().length > 10000) {
            throw new ScriptException("Script larger than 10,000 bytes");
        }

        const stack: Uint8Array[] = [];
        let p2shStack: Uint8Array[] | null = null;

        Script.executeScript(txContainingThis, scriptSigIndex, this, stack, verifyFlags);
        if (verifyFlags.has(Script.VerifyFlag.P2SH)) {
            p2shStack = [...stack]; // Copy stack
        }
        Script.executeScript(txContainingThis, scriptSigIndex, scriptPubKey, stack, verifyFlags);

        if (stack.length === 0) {
            throw new ScriptException("Stack empty at end of script execution.");
        }

        if (!Script.castToBool(stack.pop()!)) {
            throw new ScriptException(`Script resulted in a non-true stack: ${stack}`);
        }

        // P2SH is pay to script hash. It means that the scriptPubKey has a special form which is a valid
        // program but it has "useless" form that if evaluated as a normal program always returns true.
        // Instead, miners recognize it as special based on its template - it provides a hash of the real scriptPubKey
        // and that must be provided by the input. The goal of this bizarre arrangement is twofold:
        //
        // (1) You can sum up a large, complex script (like a CHECKMULTISIG script) with an address that's the same
        //     size as a regular address. This means it doesn't overload scannable QR codes/NFC tags or become
        //     un-wieldy to copy/paste.
        // (2) It allows the working set to be smaller: nodes perform best when they can store as many unspent outputs
        //     in RAM as possible, so if the outputs are made smaller and the inputs get bigger, then it's better for
        //     overall scalability and performance.

        // TODO: Check if we can take out enforceP2SH if there's a checkpoint at the enforcement block.
        if (verifyFlags.has(Script.VerifyFlag.P2SH) && scriptPubKey.isPayToScriptHash()) {
            for (const chunk of this.chunks) {
                if (chunk.isOpCode() && chunk.opcode > OP_16) {
                    throw new ScriptException("Attempted to spend a P2SH scriptPubKey with a script that contained script ops");
                }
            }

            const scriptPubKeyBytes = p2shStack!.pop()!;
            const scriptPubKeyP2SH = new Script(scriptPubKeyBytes);

            Script.executeScript(txContainingThis, scriptSigIndex, scriptPubKeyP2SH, p2shStack!, verifyFlags);

            if (p2shStack!.length === 0) {
                throw new ScriptException("P2SH stack empty at end of script execution.");
            }

            if (!Script.castToBool(p2shStack!.pop()!)) {
                throw new ScriptException("P2SH script execution resulted in a non-true stack");
            }
        }
    }

    // Utility that doesn't copy for internal use
    private getQuickProgram(): Uint8Array {
        if (this.program != null && this.program.length > 0) {
            return this.program;
        }
        return this.getProgram();
    }

    /**
     * Get the {@link net.bigtangle.script.Script.ScriptType}.
     * @return The script type.
     */
    getScriptType(): Script.ScriptType {
        let type = Script.ScriptType.NO_TYPE;
        if (this.isSentToAddress()) {
            type = Script.ScriptType.P2PKH;
        } else if (this.isSentToRawPubKey()) {
            type = Script.ScriptType.PUB_KEY;
        } else if (this.isPayToScriptHash()) {
            type = Script.ScriptType.P2SH;
        }
        return type;
    }

    equals(o: any): boolean {
        if (this === o) return true;
        if (o == null || !(o instanceof Script)) return false;
        return Utils.arraysEqual(this.getQuickProgram(), (o as Script).getQuickProgram());
    }

    hashCode(): number {
        // Simple hash function for Uint8Array
        const bytes = this.getQuickProgram();
        let hash = 0;
        for (let i = 0; i < bytes.length; i++) {
            hash = ((hash << 5) - hash) + bytes[i];
            hash |= 0; // Convert to 32bit integer
        }
        return hash;
    }
}

// Namespace for enums
export namespace Script {
    export enum ScriptType {
        NO_TYPE,
        P2PKH,
        PUB_KEY,
        P2SH
    }

    export enum VerifyFlag {
        P2SH,
        STRICTENC,
        DERSIG,
        LOW_S,
        NULLDUMMY,
        SIGPUSHONLY,
        MINIMALDATA,
        DISCOURAGE_UPGRADABLE_NOPS,
        CLEANSTACK,
        CHECKLOCKTIMEVERIFY
    }
    export const ALL_VERIFY_FLAGS = new Set<VerifyFlag>(
        Object.values(VerifyFlag).filter(v => typeof v === "number") as VerifyFlag[]
    );
}
