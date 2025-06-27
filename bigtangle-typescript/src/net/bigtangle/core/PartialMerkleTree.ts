import { Sha256Hash } from './Sha256Hash';
import { NetworkParameters } from './NetworkParameters';
import { Message } from './Message';
import { ProtocolException } from '../exception/Exceptions';
import { VerificationException } from '../exception/Exceptions';
import { VarInt } from './VarInt';
import { Utils } from '../utils/Utils';
import { Buffer } from 'buffer';

/**
 * <p>A data structure that contains proofs of block inclusion for one or more transactions, in an efficient manner.</p>
 *
 * <p>The encoding works as follows: we traverse the tree in depth-first order, storing a bit for each traversed node,
 * signifying whether the node is the parent of at least one matched leaf txid (or a matched txid itself). In case we
 * are at the leaf level, or this bit is 0, its merkle node hash is stored, and its children are not explored further.
 * Otherwise, no hash is stored, but we recurse into both (or the only) child branch. During decoding, the same
 * depth-first traversal is performed, consuming bits and hashes as they were written during encoding.</p>
 *
 * <p>The serialization is fixed and provides a hard guarantee about the encoded size,
 * <tt>SIZE &lt;= 10 + ceil(32.25*N)</tt> where N represents the number of leaf nodes of the partial tree. N itself
 * is bounded by:</p>
 *
 * <p>
 * N &lt;= total_transactions<br>
 * N &lt;= 1 + matched_transactions*tree_height
 * </p>
 *
 * <p><pre>The serialization format:
 *  - uint32     total_transactions (4 bytes)
 *  - varint     number of hashes   (1-3 bytes)
 *  - uint256[]  hashes in depth-first order (&lt;= 32*N bytes)
 *  - varint     number of bytes of flag bits (1-3 bytes)
 *  - byte[]     flag bits, packed per 8 in a byte, least significant bit first (&lt;= 2*N-1 bits)
 * The size constraints follow from this.</pre></p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
export class PartialMerkleTree extends Message {
    // the total number of transactions in the block
    private transactionCount: number = 0;

    // node-is-parent-of-matched-txid bits
    private matchedChildBits: Buffer = Buffer.alloc(0);

    // txids and internal hashes
    private hashes: Sha256Hash[] = [];
    
    constructor(params: NetworkParameters, payloadBytes?: Buffer, offset?: number) {
        super(params, payloadBytes, offset);
    }

    /**
     * Constructs a new PMT with the given bit set (little endian) and the raw list of hashes including internal hashes,
     * taking ownership of the list.
     */
    public static buildFromLeaves(params: NetworkParameters, includeBits: Buffer, allLeafHashes: Sha256Hash[]): PartialMerkleTree {
        // Calculate height of the tree.
        let height = 0;
        while (PartialMerkleTree.getTreeWidth(allLeafHashes.length, height) > 1) {
            height++;
        }
        const bitList: boolean[] = [];
        const hashes: Sha256Hash[] = [];
        PartialMerkleTree.traverseAndBuild(height, 0, allLeafHashes, includeBits, bitList, hashes);
        const bits = Buffer.alloc(Math.ceil(bitList.length / 8.0));
        for (let i = 0; i < bitList.length; i++) {
            if (bitList[i]) {
                Utils.setBitLE(bits, i);
            }
        }
        const pmt = new PartialMerkleTree(params);
        pmt.matchedChildBits = bits;
        pmt.hashes = hashes;
        pmt.transactionCount = allLeafHashes.length;
        return pmt;
    }

    protected bitcoinSerializeToStream(stream: any): void {
        stream.write(Utils.uint32ToByteArrayLE(this.transactionCount));

        stream.write(VarInt.write(this.hashes.length));
        for (const hash of this.hashes) {
            stream.write(hash.getReversedBytes());
        }

        stream.write(VarInt.write(this.matchedChildBits.length));
        stream.write(this.matchedChildBits);
    }

    protected parse(): void {
        this.transactionCount = this.readUint32();

        const nHashes = this.readVarInt();
        this.hashes = [];
        for (let i = 0; i < nHashes; i++) {
            this.hashes.push(this.readHash());
        }

        const nFlagBytes = this.readVarInt();
        this.matchedChildBits = this.readBytes(nFlagBytes);

        this.length = this.cursor - this.offset;
    }

    // Based on CPartialMerkleTree::TraverseAndBuild in Bitcoin Core.
    private static traverseAndBuild(height: number, pos: number, allLeafHashes: Sha256Hash[], includeBits: Buffer,
                                         matchedChildBits: boolean[], resultHashes: Sha256Hash[]) {
        let parentOfMatch = false;
        // Is this node a parent of at least one matched hash?
        for (let p = pos << height; p < (pos + 1) << height && p < allLeafHashes.length; p++) {
            if (Utils.checkBitLE(includeBits, p)) {
                parentOfMatch = true;
                break;
            }
        }
        // Store as a flag bit.
        matchedChildBits.push(parentOfMatch);
        if (height === 0 || !parentOfMatch) {
            // If at height 0, or nothing interesting below, store hash and stop.
            resultHashes.push(PartialMerkleTree.calcHash(height, pos, allLeafHashes));
        } else {
            // Otherwise descend into the subtrees.
            const h = height - 1;
            const p = pos * 2;
            PartialMerkleTree.traverseAndBuild(h, p, allLeafHashes, includeBits, matchedChildBits, resultHashes);
            if (p + 1 < PartialMerkleTree.getTreeWidth(allLeafHashes.length, h)) {
                PartialMerkleTree.traverseAndBuild(h, p + 1, allLeafHashes, includeBits, matchedChildBits, resultHashes);
            }
        }
    }

    private static calcHash(height: number, pos: number, hashes: Sha256Hash[]): Sha256Hash {
        if (height === 0) {
            // Hash at height 0 is just the regular tx hash itself.
            return hashes[pos];
        }
        const h = height - 1;
        const p = pos * 2;
        const left = PartialMerkleTree.calcHash(h, p, hashes);
        // Calculate right hash if not beyond the end of the array - copy left hash otherwise.
        let right: Sha256Hash;
        if (p + 1 < PartialMerkleTree.getTreeWidth(hashes.length, h)) {
            right = PartialMerkleTree.calcHash(h, p + 1, hashes);
        } else {
            right = left;
        }
        return PartialMerkleTree.combineLeftRight(left.getBytes(), right.getBytes());
    }

    // helper function to efficiently calculate the number of nodes at given height in the merkle tree
    private static getTreeWidth(transactionCount: number, height: number): number {
        return (transactionCount + (1 << height) - 1) >> height;
    }
    
    // recursive function that traverses tree nodes, consuming the bits and hashes produced by TraverseAndBuild.
    // it returns the hash of the respective node.
    private recursiveExtractHashes(height: number, pos: number, used: { bitsUsed: number, hashesUsed: number }, matchedHashes: Sha256Hash[]): Sha256Hash {
        if (used.bitsUsed >= this.matchedChildBits.length * 8) {
            // overflowed the bits array - failure
            throw new VerificationException("PartialMerkleTree overflowed its bits array");
        }
        const parentOfMatch = Utils.checkBitLE(this.matchedChildBits, used.bitsUsed++);
        if (height === 0 || !parentOfMatch) {
            // if at height 0, or nothing interesting below, use stored hash and do not descend
            if (used.hashesUsed >= this.hashes.length) {
                // overflowed the hash array - failure
                throw new VerificationException("PartialMerkleTree overflowed its hash array");
            }
            const hash = this.hashes[used.hashesUsed++];
            if (height === 0 && parentOfMatch) // in case of height 0, we have a matched txid
                matchedHashes.push(hash);
            return hash;
        } else {
            // otherwise, descend into the subtrees to extract matched txids and hashes
            const left = this.recursiveExtractHashes(height - 1, pos * 2, used, matchedHashes).getBytes();
            let right: Buffer;
            if (pos * 2 + 1 < PartialMerkleTree.getTreeWidth(this.transactionCount, height - 1)) {
                right = this.recursiveExtractHashes(height - 1, pos * 2 + 1, used, matchedHashes).getBytes();
                if (Utils.bytesEqual(right, left)) {
                    throw new VerificationException("Invalid merkle tree with duplicated left/right branches");
                }
            } else {
                right = left;
            }
            // and combine them before returning
            return PartialMerkleTree.combineLeftRight(left, right);
        }
    }

    private static combineLeftRight(left: Buffer, right: Buffer): Sha256Hash {
        return Sha256Hash.wrapReversed(Sha256Hash.hashTwice(
            Utils.reverseBytes(left), 0, 32,
            Utils.reverseBytes(right), 0, 32));
    }

    /**
     * Extracts tx hashes that are in this merkle tree
     * and returns the merkle root of this tree.
     * 
     * The returned root should be checked against the
     * merkle root contained in the block header for security.
     * 
     * @param matchedHashesOut A list which will contain the matched txn (will be cleared).
     * @return the merkle root of this merkle tree
     * @throws ProtocolException if this partial merkle tree is invalid
     */
    public getTxnHashAndMerkleRoot(matchedHashesOut: Sha256Hash[]): Sha256Hash {
        matchedHashesOut.length = 0;
        
        // An empty set will not work
        if (this.transactionCount === 0) {
            throw new VerificationException("Got a CPartialMerkleTree with 0 transactions");
        }
        // check for excessively high numbers of transactions
        if (this.transactionCount > NetworkParameters.MAX_DEFAULT_BLOCK_SIZE / 60) // 60 is the lower bound for the size of a serialized CTransaction
            throw new VerificationException("Got a CPartialMerkleTree with more transactions than is possible");
        // there can never be more hashes provided than one for every txid
        if (this.hashes.length > this.transactionCount)
            throw new VerificationException("Got a CPartialMerkleTree with more hashes than transactions");
        // there must be at least one bit per node in the partial tree, and at least one node per hash
        if (this.matchedChildBits.length * 8 < this.hashes.length)
            throw new VerificationException("Got a CPartialMerkleTree with fewer matched bits than hashes");
        // calculate height of tree
        let height = 0;
        while (PartialMerkleTree.getTreeWidth(this.transactionCount, height) > 1) {
            height++;
        }
        // traverse the partial tree
        const used = { bitsUsed: 0, hashesUsed: 0 };
        const merkleRoot = this.recursiveExtractHashes(height, 0, used, matchedHashesOut);
        // verify that all bits were consumed (except for the padding caused by serializing it as a byte sequence)
        if (Math.ceil(used.bitsUsed / 8) !== this.matchedChildBits.length ||
                // verify that all hashes were consumed
                used.hashesUsed !== this.hashes.length) {
            throw new VerificationException("Got a CPartialMerkleTree that didn't need all the data it provided");
        }
        
        return merkleRoot;
    }

    public getTransactionCount(): number {
        return this.transactionCount;
    }

    public equals(o: any): boolean {
        if (this === o) return true;
        if (o === null || !(o instanceof PartialMerkleTree)) return false;
        const other = o as PartialMerkleTree;
        return this.transactionCount === other.transactionCount &&
               this.hashes.every((hash, i) => hash.equals(other.hashes[i])) &&
               this.matchedChildBits.equals(other.matchedChildBits);
    }

    public hashCode(): number {
        let result = 17;
        result = 31 * result + this.transactionCount;
        for (const hash of this.hashes) {
            result = 31 * result + hash.hashCode();
        }
        for (let i = 0; i < this.matchedChildBits.length; i++) {
            result = 31 * result + this.matchedChildBits[i];
        }
        return result;
    }

    public toString(): string {
        return `PartialMerkleTree{` +
                `transactionCount=${this.transactionCount}, ` +
                `matchedChildBits=${Utils.HEX.encode(this.matchedChildBits)}, ` +
                `hashes=${this.hashes.map(h => h.toString())}` +
                `}`; 
    }
}
