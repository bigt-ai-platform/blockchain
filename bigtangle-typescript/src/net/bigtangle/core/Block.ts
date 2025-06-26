import { NetworkParameters } from './NetworkParameters';
import { Sha256Hash } from './Sha256Hash';
import { Transaction } from './Transaction';
import { Utils } from './Utils';
import { ProtocolException } from './exception/Exceptions';
import { MessageSerializer } from './MessageSerializer';
import { Buffer } from 'buffer';
import { VarInt } from './VarInt';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';
import { Message } from './Message';

// Define a simple interface for our output stream
interface OutputStream {
    write(chunk: Buffer): void;
}

export class Block extends Message {
    private static readonly BLOCK_VERSION_GENESIS = 1;
    private static readonly BLOCK_VERSION_AUXPOW = (1 << 8);

    private version: number = 0;
    private prevBlockHash: Sha256Hash = Sha256Hash.ZERO_HASH;
    private merkleRoot: Sha256Hash = Sha256Hash.ZERO_HASH;
    private time: number = 0;
    private difficultyTarget: number = 0;
    private nonce: number = 0;
    private transactions: Transaction[] = [];
    
    // Initialize base Message properties
    protected payload: Buffer = Buffer.alloc(0);
    protected offset: number = 0;
    protected cursor: number = 0;
    protected length: number = Message.UNKNOWN_LENGTH;
    protected serializer: any = null;

    constructor(params: NetworkParameters, bytes?: Buffer, offset?: number, serializer?: MessageSerializer, length?: number) {
        super(params, bytes, offset, serializer, length);
        this.params = params;
        
        if (bytes) {
            this.parse(bytes, offset || 0, length);
        }
    }

    protected parse(payload?: Buffer, offset: number = 0, length?: number): void {
        if (!payload) return;
        // Parse block header
        this.version = payload.readUInt32LE(offset);
        offset += 4;
        
        this.prevBlockHash = Sha256Hash.wrapReversed(payload.subarray(offset, offset + 32));
        offset += 32;
        
        this.merkleRoot = Sha256Hash.wrapReversed(payload.subarray(offset, offset + 32));
        offset += 32;
        
        this.time = payload.readUInt32LE(offset);
        offset += 4;
        
        this.difficultyTarget = payload.readUInt32LE(offset);
        offset += 4;
        
        this.nonce = payload.readUInt32LE(offset);
        offset += 4;

        // Parse transactions
        const txCountResult = VarInt.read(payload, offset);
        offset += txCountResult.size;
        
        for (let i = 0; i < txCountResult.value; i++) {
            const tx = new Transaction(this.params, payload, offset, this.serializer);
            this.transactions.push(tx);
            offset += tx.getMessageSize();
        }
    }

    public bitcoinSerialize(): Uint8Array {
        const out = new UnsafeByteArrayOutputStream();
        
        // Serialize header
        const header = Buffer.alloc(80);
        header.writeUInt32LE(this.version, 0);
        
        // Manually copy bytes since Uint8Array doesn't have copy method
        const prevBlockBytes = this.prevBlockHash.getReversedBytes();
        for (let i = 0; i < 32; i++) {
            header[4 + i] = prevBlockBytes[i];
        }
        
        const merkleBytes = this.merkleRoot.getReversedBytes();
        for (let i = 0; i < 32; i++) {
            header[36 + i] = merkleBytes[i];
        }
        
        header.writeUInt32LE(this.time, 68);
        header.writeUInt32LE(this.difficultyTarget, 72);
        header.writeUInt32LE(this.nonce, 76);
        out.write(header);

        // Serialize transactions
        VarInt.write(this.transactions.length, out);
        for (const tx of this.transactions) {
            out.write(Buffer.from(tx.bitcoinSerialize()));
        }

        return out.toBuffer();
    }
    
    protected bitcoinSerializeToStream(stream: any): void {
        const bytes = this.bitcoinSerialize();
        stream.write(Buffer.from(bytes));
    }

    public getMessageSize(): number {
        let size = 80; // Block header size
        size += VarInt.sizeOf(this.transactions.length);
        for (const tx of this.transactions) {
            size += tx.getMessageSize();
        }
        return size;
    }

    // Getters and setters
    public getVersion(): number { return this.version; }
    public setVersion(version: number): void { this.version = version; }
    
    public getPrevBlockHash(): Sha256Hash { return this.prevBlockHash; }
    public setPrevBlockHash(hash: Sha256Hash): void { this.prevBlockHash = hash; }
    
    public getMerkleRoot(): Sha256Hash { return this.merkleRoot; }
    public setMerkleRoot(root: Sha256Hash): void { this.merkleRoot = root; }
    
    public getTime(): number { return this.time; }
    public setTime(time: number): void { this.time = time; }
    
    public getDifficultyTarget(): number { return this.difficultyTarget; }
    public setDifficultyTarget(target: number): void { this.difficultyTarget = target; }
    
    public getNonce(): number { return this.nonce; }
    public setNonce(nonce: number): void { this.nonce = nonce; }
    
    public getTransactions(): Transaction[] { return this.transactions; }
    public setTransactions(txs: Transaction[]): void { this.transactions = txs; }

    public getParams(): NetworkParameters { return this.params; }
    public setParams(params: NetworkParameters): void { this.params = params; }

    // Genesis block creation helper
    public static createGenesisBlock(params: NetworkParameters, time: number): Block {
        const genesisBlock = new Block(params);
        genesisBlock.setVersion(Block.BLOCK_VERSION_GENESIS);
        genesisBlock.setTime(time);
        genesisBlock.setDifficultyTarget(0x1d00ffff);
        genesisBlock.setNonce(2083236893);
        return genesisBlock;
    }
}
