import { Block } from './Block';
import { PartialMerkleTree } from './PartialMerkleTree';
import { Sha256Hash } from './Sha256Hash';
import { Transaction } from './Transaction';
import { NetworkParameters } from './NetworkParameters';
import { Message } from './Message';
 
import { VerificationException } from '../exception/VerificationException';
import { Buffer } from 'buffer';

/**
 * <p>A FilteredBlock is used to relay a block with its transactions filtered using a {@link BloomFilter}. It consists
 * of the block header and a {@link PartialMerkleTree} which contains the transactions which matched the filter.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
export class FilteredBlock extends Message {
    private header: Block;
    private merkleTree: PartialMerkleTree;
    private cachedTransactionHashes: Sha256Hash[] | null = null;
    private associatedTransactions: Map<Sha256Hash, Transaction> = new Map();

    constructor(params: NetworkParameters, payloadBytes: Buffer);
    constructor(params: NetworkParameters, header: Block, pmt: PartialMerkleTree);
    constructor(params: NetworkParameters, arg1: Buffer | Block, arg2?: PartialMerkleTree) {
        super(params);
        if (arg1 instanceof Buffer) {
            // Constructor(params, payloadBytes)
            this.header = new Block(params); // Placeholder, will be parsed from payload
            this.merkleTree = new PartialMerkleTree(params, Buffer.alloc(0), 0); // Placeholder
            this.parse(arg1);
        } else {
            // Constructor(params, header, pmt)
            if (arg1 instanceof Block) {
                this.header = arg1;
            } else {
                throw new Error("Expected Block for header argument");
            }
            this.merkleTree = arg2 as PartialMerkleTree;
        }
    }

    protected bitcoinSerializeToStream(stream: any): void {
        if (this.header.getTransactions() === null) {
            this.header.bitcoinSerializeToStream(stream);
        } else {
            this.header.cloneAsHeader().bitcoinSerializeToStream(stream);
        }
        // Assuming PartialMerkleTree has a bitcoinSerializeToStream method
        // this.merkleTree.bitcoinSerializeToStream(stream);
        stream.write(this.mermerTree.bitcoinSerialize());
    }

    protected parse(payload: Buffer): void {
        const headerBytes = payload.subarray(0, NetworkParameters.HEADER_SIZE);
        this.header = this.params.getDefaultSerializer().makeBlock(headerBytes, 0, NetworkParameters.HEADER_SIZE);
        
        this.merkleTree = new PartialMerkleTree(this.params, payload, NetworkParameters.HEADER_SIZE);
        
        this.length = NetworkParameters.HEADER_SIZE + this.merkleTree.getMessageSize();
    }
    
    /**
     * Gets a list of leaf hashes which are contained in the partial merkle tree in this filtered block
     * 
     * @throws ProtocolException If the partial merkle block is invalid or the merkle root of the partial merkle block doesnt match the block header
     */
    public getTransactionHashes(): Sha256Hash[] {
        if (this.cachedTransactionHashes !== null) {
            return [...this.cachedTransactionHashes];
        }
        const hashesMatched: Sha256Hash[] = [];
        if (this.header.getMerkleRoot().equals(this.merkleTree.getTxnHashAndMerkleRoot(hashesMatched))) {
            this.cachedTransactionHashes = hashesMatched;
            return [...this.cachedTransactionHashes];
        } else {
            throw new VerificationException("Merkle tree did not verify");
        }
    }
    
    /**
     * Gets a copy of the block header
     */
    public getBlockHeader(): Block {
        return this.header.cloneAsHeader();
    }
    
    /** Gets the hash of the block represented in this Filtered Block */
    public getHash(): Sha256Hash {
        return this.header.getHash();
    }
    
    /**
     * Provide this FilteredBlock with a transaction which is in its Merkle tree.
     * @return false if the tx is not relevant to this FilteredBlock
     */
    public provideTransaction(tx: Transaction): boolean {
        const hash = tx.getHash();
        if (this.getTransactionHashes().some(h => h.equals(hash))) {
            this.associatedTransactions.set(hash, tx);
            return true;
        }
        return false;
    }

    /** Returns the {@link PartialMerkleTree} object that provides the mathematical proof of transaction inclusion in the block. */
    public getPartialMerkleTree(): PartialMerkleTree {
        return this.merkleTree;
    }

    /** Gets the set of transactions which were provided using provideTransaction() which match in getTransactionHashes() */
    public getAssociatedTransactions(): Map<Sha256Hash, Transaction> {
        return new Map(this.associatedTransactions);
    }

    /** Number of transactions in this block, before it was filtered */
    public getTransactionCount(): number {
        return this.merkleTree.getTransactionCount();
    }

    public equals(o: any): boolean {
        if (this === o) return true;
        if (o === null || !(o instanceof FilteredBlock)) return false;
        const other = o as FilteredBlock;
        return this.associatedTransactions.size === other.associatedTransactions.size &&
               Array.from(this.associatedTransactions.keys()).every(key => {
                   const otherTx = other.associatedTransactions.get(key);
                   return otherTx && this.associatedTransactions.get(key)!.equals(otherTx);
               }) &&
               this.header.equals(other.header) &&
               this.merkleTree.equals(other.merkleTree);
    }

    public hashCode(): number {
        let result = 17;
        for (const [key, value] of this.associatedTransactions.entries()) {
            result = 31 * result + key.hashCode();
            result = 31 * result + value.hashCode();
        }
        result = 31 * result + this.header.hashCode();
        result = 31 * result + this.merkleTree.hashCode();
        return result;
    }

    public toString(): string {
        return `FilteredBlock{merkleTree=${this.merkleTree}, header=${this.header}}`;
    }
}
