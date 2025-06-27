import { Message } from './Message';
import { NetworkParameters } from './NetworkParameters';
import {  VerificationException } from '../exception/VerificationException';

import { MessageSerializer } from './MessageSerializer';
import { Sha256Hash } from './Sha256Hash';
import { Transaction } from './Transaction';
import { TransactionInput } from './TransactionInput';
import { TransactionOutput } from './TransactionOutput';
import { VarInt } from './VarInt';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';
import { Utils } from '../utils/Utils';
import { Buffer } from 'buffer';
import { BigInteger } from 'jsbn';
import { ScriptBuilder } from '../script/ScriptBuilder';
import { ECKey } from './ECKey';
import { Coin } from './Coin';
import { TokenInfo } from './TokenInfo';
import { MemoInfo } from './MemoInfo';
import { DataClassName } from './DataClassName';
import { BlockType, BlockTypeConfigs, allowCoinbaseTransaction as blockTypeAllowCoinbaseTransaction, getMaxBlockSize as blockTypeGetMaxBlockSize } from './BlockType';
export class Block extends Message {
    private static readonly log = console;

    private version!: number;
    private prevBlockHash!: Sha256Hash;
    private prevBranchBlockHash!: Sha256Hash;
    private merkleRoot!: Sha256Hash;
    private time!: number;
    private difficultyTarget!: number;
    private lastMiningRewardBlock!: number;
    private nonce!: number;
    private minerAddress!: Buffer;
    private blockType!: BlockType;
    private height!: number;

    public transactions!: Transaction[] | null;

    private hash!: Sha256Hash | null;

    protected headerBytesValid!: boolean;
    protected transactionBytesValid!: boolean;
    protected optimalEncodingMessageSize!: number;

    public constructor(params: NetworkParameters, setVersion: number);
    public constructor(params: NetworkParameters, prevBlockHash: Sha256Hash, prevBranchBlockHash: Sha256Hash, blocktype: number, minTime: number, lastMiningRewardBlock: number, difficultyTarget: number);
    public constructor(params: NetworkParameters, payloadBytes: Buffer, serializer: MessageSerializer, length: number);
    public constructor(params: NetworkParameters, payloadBytes: Buffer, offset: number, serializer: MessageSerializer, length: number);
    public constructor(params: NetworkParameters, payloadBytes: Buffer, offset: number, parent: Message | null, serializer: MessageSerializer, length: number);
    public constructor(params: NetworkParameters, ...args: any[]) {
        super(params);
        if (args.length === 1) {
            const setVersion = args[0];
            this.version = setVersion;
            this.difficultyTarget = Utils.encodeCompactBits(new BigInteger(params.getMaxTarget().toString()));
            this.lastMiningRewardBlock = 0;
            this.time = Math.floor(Date.now() / 1000);
            this.prevBlockHash = Sha256Hash.ZERO_HASH;
            this.prevBranchBlockHash = Sha256Hash.ZERO_HASH;
            this.blockType = BlockType.BLOCKTYPE_TRANSFER;
            this.minerAddress = Buffer.alloc(20);
            this.length = NetworkParameters.HEADER_SIZE;
            this.transactions = [];
        } else if (args.length === 6) {
            const [prevBlockHash, prevBranchBlockHash, blocktype, minTime, lastMiningRewardBlock, difficultyTarget] = args;
            this.version = NetworkParameters.BLOCK_VERSION_GENESIS;
            this.difficultyTarget = difficultyTarget;
            this.lastMiningRewardBlock = lastMiningRewardBlock;
            this.time = Math.floor(Date.now() / 1000);
            if (this.time < minTime)
                this.time = minTime;
            this.prevBlockHash = prevBlockHash;
            this.prevBranchBlockHash = prevBranchBlockHash;
            this.blockType = blocktype;
            this.minerAddress = Buffer.alloc(20);
            this.length = NetworkParameters.HEADER_SIZE;
            this.transactions = [];
        } else if (args.length === 3) {
            const [payloadBytes, serializer, length] = args;
            this.serializer = serializer;
            this.payload = payloadBytes;
            this.offset = 0;
            this.cursor = this.offset;
            this.length = length;
            this.parse();
        } else if (args.length === 4) {
            const [payloadBytes, offset, serializer, length] = args;
            this.serializer = serializer;
            this.payload = payloadBytes;
            this.offset = offset;
            this.cursor = this.offset;
            this.length = length;
            this.parse();
        } else if (args.length === 5) {
            const [payloadBytes, offset, parent, serializer, length] = args;
            this.serializer = serializer;
            this.payload = payloadBytes;
            this.offset = offset;
            this.cursor = this.offset;
            this.length = length;
            this.parse();
        }
    }

    public isBLOCKTYPE_INITIAL(): boolean {
        return this.getBlockType() === BlockType.BLOCKTYPE_INITIAL;
    }

    protected parseTransactions(transactionsOffset: number): void {
        this.cursor = transactionsOffset;
        this.optimalEncodingMessageSize = NetworkParameters.HEADER_SIZE;
        if (this.payload.length === this.cursor) {
            this.transactionBytesValid = false;
            return;
        }

        const numTransactions = this.readVarInt();
        this.optimalEncodingMessageSize += VarInt.sizeOf(numTransactions);
        this.transactions = new Array(numTransactions);
        for (let i = 0; i < numTransactions; i++) {
            const tx = new Transaction(this.params, this.payload, this.cursor, this.serializer);
            this.transactions[i] = tx;
            this.cursor += tx.getMessageSize();
            this.optimalEncodingMessageSize += tx.getMessageSize();
        }
        this.transactionBytesValid = this.serializer.isParseRetainMode();
    }

    protected parse(): void {
        this.cursor = this.offset;
        this.version = this.readUint32();
        this.prevBlockHash = this.readHash();
        this.prevBranchBlockHash = this.readHash();
        this.merkleRoot = this.readHash();
        this.time = this.readInt64();
        this.difficultyTarget = this.readInt64();
        this.lastMiningRewardBlock = this.readInt64();
        this.nonce = this.readUint32();
        this.minerAddress = this.readBytes(20);
        this.blockType = this.readUint32();
        this.height = this.readInt64();
        this.hash = Sha256Hash.wrapReversed(Sha256Hash.hashTwice(this.payload.slice(this.offset, this.cursor)).getBytes());
        this.headerBytesValid = this.serializer.isParseRetainMode();
        this.parseTransactions(this.cursor);
        this.length = this.cursor - this.offset;
    }

    public getOptimalEncodingMessageSize(): number {
        if (this.optimalEncodingMessageSize !== 0)
            return this.optimalEncodingMessageSize;
        this.optimalEncodingMessageSize = this.bitcoinSerialize().length;
        return this.optimalEncodingMessageSize;
    }

    private writeHeader(stream: UnsafeByteArrayOutputStream): void {
        if (this.headerBytesValid && this.payload !== null && this.payload.length >= this.offset + NetworkParameters.HEADER_SIZE) {
            stream.write(this.payload.slice(this.offset, this.offset + NetworkParameters.HEADER_SIZE));
            return;
        }

        const buf = Buffer.alloc(NetworkParameters.HEADER_SIZE);
        let offset = 0;
        buf.writeUInt32LE(this.version, offset); offset += 4;
        this.prevBlockHash.getReversedBytes().copy(buf, offset); offset += 32;
        this.prevBranchBlockHash.getReversedBytes().copy(buf, offset); offset += 32;
        this.getMerkleRoot().getReversedBytes().copy(buf, offset); offset += 32;
        buf.writeBigUInt64LE(BigInt(this.time), offset); offset += 8;
        buf.writeBigUInt64LE(BigInt(this.difficultyTarget), offset); offset += 8;
        buf.writeBigUInt64LE(BigInt(this.lastMiningRewardBlock), offset); offset += 8;
        buf.writeUInt32LE(this.nonce, offset); offset += 4;
        this.minerAddress.copy(buf, offset); offset += 20;
        buf.writeUInt32LE(this.blockType, offset); offset += 4;
        buf.writeBigUInt64LE(BigInt(this.height), offset);
        stream.write(buf);
    }

    private writeTransactions(stream: UnsafeByteArrayOutputStream): void {
        if (this.transactions === null) {
            return;
        }

        if (this.transactionBytesValid && this.payload !== null && this.payload.length >= this.offset + this.length) {
            stream.write(this.payload.slice(this.offset + NetworkParameters.HEADER_SIZE, this.offset + this.length));
            return;
        }

        VarInt.write(this.transactions.length, stream);
        for (const tx of this.transactions) {
            tx.bitcoinSerializeToStream(stream);
        }
    }

    public bitcoinSerialize(): Buffer {
        if (this.headerBytesValid && this.transactionBytesValid) {
            if (this.length === this.payload.length) {
                return this.payload;
            } else {
                const buf = Buffer.alloc(this.length);
                this.payload.copy(buf, 0, this.offset, this.offset + this.length);
                return buf;
            }
        }

        const stream = new UnsafeByteArrayOutputStream(this.length === Message.UNKNOWN_LENGTH ? NetworkParameters.HEADER_SIZE + this.guessTransactionsLength() : this.length);
        this.writeHeader(stream);
        this.writeTransactions(stream);
        return stream.toByteArray();
    }

    protected bitcoinSerializeToStream(stream: UnsafeByteArrayOutputStream): void {
        this.writeHeader(stream);
        this.writeTransactions(stream);
    }

    private guessTransactionsLength(): number {
        if (this.transactionBytesValid)
            return this.payload.length - NetworkParameters.HEADER_SIZE;
        if (this.transactions === null)
            return 0;
        let len = VarInt.sizeOf(this.transactions.length);
        for (const tx of this.transactions) {
            len += tx.length === Message.UNKNOWN_LENGTH ? 255 : tx.length;
        }
        return len;
    }

    public unCache(): void {
        this.unCacheTransactions();
    }

    private unCacheHeader(): void {
        this.headerBytesValid = false;
        if (!this.transactionBytesValid)
            this.payload = Buffer.alloc(0);
        this.hash = null;
    }

    private unCacheTransactions(): void {
        this.transactionBytesValid = false;
        if (!this.headerBytesValid)
            this.payload = Buffer.alloc(0);
        this.unCacheHeader();
        this.merkleRoot = Sha256Hash.ZERO_HASH;
    }

    private calculateHash(): Sha256Hash {
        const stream = new UnsafeByteArrayOutputStream(NetworkParameters.HEADER_SIZE);
        this.writeHeader(stream);
        return Sha256Hash.wrapReversed(Sha256Hash.hashTwice(stream.toByteArray()).getBytes());
    }

    private calculatePoWHash(): Sha256Hash {
        const stream = new UnsafeByteArrayOutputStream(NetworkParameters.HEADER_SIZE);
        this.writeHeader(stream);
        return Sha256Hash.wrapReversed(Sha256Hash.hashTwice(stream.toByteArray()).getBytes());
    }

    public getHashAsString(): string {
        return this.getHash().toString();
    }

    public getHash(): Sha256Hash {
        this.hash ??= this.calculateHash();
        return this.hash;
    }

    private static readonly LARGEST_HASH = new BigInteger("1").shiftLeft(256);

    public getWork(): BigInteger {
        const target = this.getDifficultyTargetAsInteger();
        return Block.LARGEST_HASH.divide(target.add(BigInteger.ONE));
    }

    public getDifficultyTargetAsInteger(): BigInteger {
        const target = Utils.decodeCompactBits(this.difficultyTarget);
        if (target.signum() < 0 || target.compareTo(new BigInteger(this.params.getMaxTarget().toString())) > 0)
            throw new VerificationException.DifficultyTargetException();
        return target;
    }

    public checkProofOfWork(throwException: boolean, target?: BigInteger): boolean {
        if (this.getBlockType() === BlockType.BLOCKTYPE_INITIAL) {
            return true;
        }

        const powHash = this.calculatePoWHash();
        const hashHex = powHash.getBytes().toString('hex');
        const h = new BigInteger(hashHex, 16);
        if (!target) throw new Error("Difficulty target is required for PoW check");
        if (h.compareTo(target) > 0) {
            if (throwException)
                throw new VerificationException.ProofOfWorkException();
            else
                return false;
        }
        return true;
    }

    private checkTimestamp(): void {
        const currentTime = Utils.currentTimeSeconds();
        if (this.time > currentTime + NetworkParameters.ALLOWED_TIME_DRIFT)
            throw new VerificationException.TimeTravelerException();
    }

    private checkSigOps(): void {
        let sigOps = 0;
        if (this.transactions === null)
            return;
        for (const tx of this.transactions) {
            sigOps += tx.getSigOpCount();
        }
        if (sigOps > NetworkParameters.MAX_BLOCK_SIGOPS)
            throw new VerificationException.SigOpsException();
    }

    private checkMerkleRoot(): void {
        const calculatedRoot = this.calculateMerkleRoot();
        if (!calculatedRoot.equals(this.merkleRoot)) {
            Block.log.error("Merkle tree did not verify");
            throw new VerificationException.MerkleRootMismatchException();
        }
    }

    private calculateMerkleRoot(): Sha256Hash {
        const tree = this.buildMerkleTree();
        if (tree.length === 0)
            return Sha256Hash.ZERO_HASH;
        return Sha256Hash.wrap(tree[tree.length - 1]);
    }

    private buildMerkleTree(): Buffer[] {
        const tree: Buffer[] = [];
        if (this.transactions === null)
            this.transactions = [];
        for (const t of this.transactions) {
            tree.push(t.getHash().getBytes());
        }
        let levelOffset = 0;
        for (let levelSize = this.transactions.length; levelSize > 1; levelSize = Math.floor((levelSize + 1) / 2)) {
            for (let left = 0; left < levelSize; left += 2) {
                const right = Math.min(left + 1, levelSize - 1);
                const leftBytes = Utils.reverseBytes(tree[levelOffset + left]);
                const rightBytes = Utils.reverseBytes(tree[levelOffset + right]);
                const concat = Buffer.concat([leftBytes, rightBytes]);
                const hash = Sha256Hash.hashTwice(concat);
                tree.push(Buffer.from(Utils.reverseBytes(Buffer.from(hash.getBytes()))));
            }
            levelOffset += levelSize;
        }
        return tree;
    }

    public verifyHeader(): void {
        this.checkProofOfWork(true, this.getDifficultyTargetAsInteger());
        this.checkTimestamp();
    }

    public verifyTransactions(): void {
        if (this.getOptimalEncodingMessageSize() > this.getMaxBlockSize())
            throw new VerificationException.LargerThanMaxBlockSize();
        this.checkMerkleRoot();
        this.checkSigOps();
        if (this.transactions === null)
            return;
        for (const transaction of this.transactions) {
            if (!this.allowCoinbaseTransaction() && transaction.isCoinBase()) {
                throw new VerificationException.CoinbaseDisallowedException();
            }
            transaction.verify();
        }
    }

    public verify(): void {
        this.verifyHeader();
        this.verifyTransactions();
    }

    public equals(o: any): boolean {
        if (this === o)
            return true;
        if (o === null || this.constructor !== o.constructor)
            return false;
        return this.getHash().equals((o as Block).getHash());
    }

    public hashCode(): number {
        // Compute a simple hash code from the bytes of the hash
        const bytes = this.getHash().getBytes();
        let hash = 0;
        for (let i = 0; i < bytes.length; i++) {
            hash = ((hash << 5) - hash) + bytes[i];
            hash |= 0; // Convert to 32bit integer
        }
        return hash;
    }

    public getMerkleRoot(): Sha256Hash {
        if (this.merkleRoot === null) {
            this.unCacheHeader();
            this.merkleRoot = this.calculateMerkleRoot();
        }
        return this.merkleRoot;
    }

    public setMerkleRoot(value: Sha256Hash): void {
        this.unCacheHeader();
        this.merkleRoot = value;
        this.hash = null;
    }

    public addTransaction(t: Transaction): void {
        this.unCacheTransactions();
        if (this.transactions === null) {
            this.transactions = [];
        }
        t.setParent(this);
        this.transactions.push(t);
        this.adjustLength(this.transactions.length, t.length);
        this.merkleRoot = Sha256Hash.ZERO_HASH;
        this.hash = null;
    }

    public getVersion(): number {
        return this.version;
    }

    public getPrevBlockHash(): Sha256Hash {
        return this.prevBlockHash;
    }

    public setPrevBlockHash(prevBlockHash: Sha256Hash): void {
        this.unCacheHeader();
        this.prevBlockHash = prevBlockHash;
        this.hash = null;
    }

    public getPrevBranchBlockHash(): Sha256Hash {
        return this.prevBranchBlockHash;
    }

    public setPrevBranchBlockHash(prevBranchBlockHash: Sha256Hash): void {
        this.unCacheHeader();
        this.prevBranchBlockHash = prevBranchBlockHash;
        this.hash = null;
    }

    public getTimeSeconds(): number {
        return this.time;
    }

    public getTime(): Date {
        return new Date(this.getTimeSeconds() * 1000);
    }

    public setTime(time: number): void {
        this.unCacheHeader();
        this.time = time;
        this.hash = null;
    }

    public getNonce(): number {
        return this.nonce;
    }

    public setNonce(nonce: number): void {
        this.unCacheHeader();
        this.nonce = nonce;
        this.hash = null;
    }

    public getTransactions(): Transaction[] {
        return this.transactions === null ? [] : [...this.transactions];
    }

    public addCoinbaseTransaction(pubKeyTo: Buffer, value: Coin, tokenInfo: TokenInfo, memoInfo: MemoInfo): void {
        this.unCacheTransactions();
        this.transactions = [];

        const coinbase = new Transaction(this.params);
        if (tokenInfo !== null) {
            coinbase.setDataClassName(DataClassName.TOKEN);
            const buf = Buffer.from(tokenInfo.toByteArray());
            coinbase.setData(buf);
        }
        coinbase.setMemo(memoInfo ? memoInfo.toString() : null);
        const inputBuilder = new ScriptBuilder();
        inputBuilder.data(Buffer.from([Block.txCounter, (Block.txCounter++ >> 8)]));
        coinbase.addInput(new TransactionInput(this.params, coinbase, Buffer.from(inputBuilder.build().getProgram())));
        if (tokenInfo === null) {
            coinbase.addOutput(new TransactionOutput(this.params, coinbase, value, Buffer.from(ScriptBuilder.createOutputScript(ECKey.fromPublic(pubKeyTo)).getProgram())));
        } else {
            const token = tokenInfo.getToken();
            if (token === null || token.getSignnumber() === 0) {
                coinbase.addOutput(new TransactionOutput(this.params, coinbase, value, Buffer.from(ScriptBuilder.createOutputScript(ECKey.fromPublic(pubKeyTo)).getProgram())));
            } else {
                const keys: ECKey[] = [];
                const multiSignAddresses = tokenInfo.getMultiSignAddresses?.() ?? [];
                for (const multiSignAddress of multiSignAddresses) {
                    if (typeof multiSignAddress.getTokenHolder === 'function' && multiSignAddress.getTokenHolder() === 1) {
                        const pubKeyHex = typeof multiSignAddress.getPubKeyHex === 'function'
                            ? multiSignAddress.getPubKeyHex()
                            : '';
                        if (pubKeyHex) {
                            const ecKey = ECKey.fromPublic(Buffer.from(pubKeyHex, 'hex'));
                            keys.push(ecKey);
                        }
                    }
                }
                if (keys.length <= 1) {
                    coinbase.addOutput(new TransactionOutput(this.params, coinbase, value, Buffer.from(ScriptBuilder.createOutputScript(ECKey.fromPublic(pubKeyTo)).getProgram())));
                } else {
                    const n = keys.length;
                    const scriptPubKey = ScriptBuilder.createMultiSigOutputScript(n, keys);
                    coinbase.addOutput(new TransactionOutput(this.params, coinbase, value, Buffer.from(scriptPubKey.getProgram())));
                }
            }
        }
        this.transactions.push(coinbase);
        coinbase.setParent(this);
        coinbase.length = coinbase.unsafeBitcoinSerialize().length;
        this.adjustLength(this.transactions.length, coinbase.length);
    }

    public allowCoinbaseTransaction(): boolean {
        return blockTypeAllowCoinbaseTransaction(this.blockType);
    }

    private getMaxBlockSize(): number {
        return blockTypeGetMaxBlockSize(this.blockType);
    }

    private static txCounter = 0;
    private static gen = { nextLong: () => Math.floor(Math.random() * Number.MAX_SAFE_INTEGER) };

    public hasTransactions(): boolean {
        return this.transactions !== null && this.transactions.length > 0;
    }

    public getMinerAddress(): Buffer {
        return this.minerAddress;
    }

    public setMinerAddress(mineraddress: Buffer): void {
        this.unCacheHeader();
        this.minerAddress = mineraddress;
        this.hash = null;
    }

    public getBlockType(): BlockType {
        return this.blockType;
    }

    public setBlockType(blocktype: number | BlockType): void {
        this.unCacheHeader();
        this.blockType = blocktype as BlockType;
        this.hash = null;
    }

    public getDifficultyTarget(): number {
        return this.difficultyTarget;
    }

    public setDifficultyTarget(difficultyTarget: number): void {
        this.difficultyTarget = difficultyTarget;
    }

    public getLastMiningRewardBlock(): number {
        return this.lastMiningRewardBlock;
    }

    public setLastMiningRewardBlock(lastMiningRewardBlock: number): void {
        this.lastMiningRewardBlock = lastMiningRewardBlock;
    }

    public getHeight(): number {
        return this.height;
    }

    public setHeight(height: number): void {
        this.unCacheHeader();
        this.height = height;
        this.hash = null;
    }

    /**
     * Finds a value of nonce that validates correctly for the current difficulty target.
     */
    public solve(): void {
        this.solveDifficult(this.getDifficultyTargetAsInteger());
    }

    /**
     * Finds a value of nonce that validates correctly for the given target.
     * @param target The difficulty target as a BigInteger.
     */
    public solveDifficult(target: BigInteger): void {
        // Add randomness to prevent new empty blocks from same miner with same approved blocks to be the same
        this.setNonce(Math.floor(Math.random() * Number.MAX_SAFE_INTEGER));

        while (true) {
            try {
                // Is our proof of work valid yet?
                if (this.checkProofOfWork(false, target)) {
                    return;
                }
                // No, so increment the nonce and try again.
                this.setNonce(this.getNonce() + 1);
            } catch (e) {
                if (e instanceof VerificationException) {
                    throw new Error(e.message); // Cannot happen.
                } else {
                    throw e;
                }
            }
        }
    }
}
