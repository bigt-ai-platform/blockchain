import { ECKey } from './ECKey';
import { Utils } from './Utils';
import { VarInt } from './VarInt';
import { NetworkParameters } from './NetworkParameters';
import { Message } from './Message';
import { MessageSerializer } from './MessageSerializer';

export enum BloomUpdate {
    UPDATE_NONE = 0,
    UPDATE_ALL = 1,
    UPDATE_P2PUBKEY_ONLY = 2
}

export class BloomFilter extends Message {
    private static readonly MAX_FILTER_SIZE = 36000;
    private static readonly MAX_HASH_FUNCS = 50;
    private static readonly E = Math.E;

    private data: Buffer = Buffer.alloc(0);
    private hashFuncs: number = 0;
    private nTweak: number = 0;
    private nFlags: number = 0;

    // Initialize base Message properties
    protected payload: Buffer = Buffer.alloc(0);
    protected offset: number = 0;
    protected cursor: number = 0;
    protected length: number = Message.UNKNOWN_LENGTH;
    protected serializer: any = null;

    constructor(params: NetworkParameters, bytes?: Buffer, offset: number = 0, serializer?: MessageSerializer) {
        super(params, bytes, offset, serializer);
        
        if (bytes) {
            this.parse(bytes, offset);
        }
    }

    static create(params: NetworkParameters, elements: number, falsePositiveRate: number, randomNonce: number, updateFlag: BloomUpdate = BloomUpdate.UPDATE_P2PUBKEY_ONLY): BloomFilter {
        // Calculate optimal filter size
        let size = Math.floor(-1 / (Math.pow(Math.log(2), 2)) * elements * Math.log(falsePositiveRate));
        size = Math.max(1, Math.min(size, BloomFilter.MAX_FILTER_SIZE * 8) / 8);
        
        const data = Buffer.alloc(size);
        let hashFuncs = Math.floor(data.length * 8 / elements * Math.log(2));
        hashFuncs = Math.max(1, Math.min(hashFuncs, BloomFilter.MAX_HASH_FUNCS));
        
        const filter = new BloomFilter(params);
        filter.data = data;
        filter.hashFuncs = hashFuncs;
        filter.nTweak = randomNonce;
        filter.nFlags = updateFlag;
        
        return filter;
    }

    getFalsePositiveRate(elements: number): number {
        return Math.pow(1 - Math.pow(BloomFilter.E, -1.0 * (this.hashFuncs * elements) / (this.data.length * 8)), this.hashFuncs);
    }

    toString(): string {
        return `Bloom Filter of size ${this.data.length} with ${this.hashFuncs} hash functions.`;
    }

    protected parse(payload: Buffer, offset: number = 0): void {
        let localOffset = offset;
        
        // Read data length (varint)
        let dataLength = 0;
        const firstByte = payload[localOffset];
        localOffset++;
        
        if (firstByte < 0xfd) {
            dataLength = firstByte;
        } else if (firstByte === 0xfd) {
            dataLength = payload.readUInt16LE(localOffset);
            localOffset += 2;
        } else if (firstByte === 0xfe) {
            dataLength = payload.readUInt32LE(localOffset);
            localOffset += 4;
        } else {
            dataLength = payload.readUInt32LE(localOffset) + (payload.readUInt32LE(localOffset + 4) * 0x100000000);
            localOffset += 8;
        }
        
        if (dataLength > BloomFilter.MAX_FILTER_SIZE) {
            throw new Error("Bloom filter out of size range.");
        }
        
        // Read data
        this.data = Buffer.from(payload.subarray(localOffset, localOffset + dataLength));
        localOffset += dataLength;
        
        // Read hash functions (uint32)
        this.hashFuncs = payload.readUInt32LE(localOffset);
        localOffset += 4;
        
        if (this.hashFuncs > BloomFilter.MAX_HASH_FUNCS) {
            throw new Error("Bloom filter hash function count out of range");
        }
        
        // Read nTweak (uint32)
        this.nTweak = payload.readUInt32LE(localOffset);
        localOffset += 4;
        
        // Read flags (byte)
        this.nFlags = payload[localOffset];
    }
    
    protected bitcoinSerializeToStream(stream: any): void {
        const serialized = this.serialize();
        stream.write(serialized);
    }
    
    public bitcoinSerialize(): Buffer {
        return this.serialize();
    }
    
    public getMessageSize(): number {
        return this.serialize().length;
    }

    serialize(): Buffer {
        // Manually serialize varint for data length
        const varintParts: Buffer[] = [];
        let dataLength = this.data.length;
        
        if (dataLength < 0xfd) {
            varintParts.push(Buffer.from([dataLength]));
        } else if (dataLength <= 0xffff) {
            varintParts.push(Buffer.from([0xfd]));
            const lenBuf = Buffer.alloc(2);
            lenBuf.writeUInt16LE(dataLength);
            varintParts.push(lenBuf);
        } else if (dataLength <= 0xffffffff) {
            varintParts.push(Buffer.from([0xfe]));
            const lenBuf = Buffer.alloc(4);
            lenBuf.writeUInt32LE(dataLength);
            varintParts.push(lenBuf);
        } else {
            varintParts.push(Buffer.from([0xff]));
            const low = dataLength % 0x100000000;
            const high = Math.floor(dataLength / 0x100000000);
            const lenBuf = Buffer.alloc(8);
            lenBuf.writeUInt32LE(low, 0);
            lenBuf.writeUInt32LE(high, 4);
            varintParts.push(lenBuf);
        }
        
        const varintBuf = Buffer.concat(varintParts);
        const buffer = Buffer.alloc(varintBuf.length + this.data.length + 4 + 4 + 1);
        let offset = 0;
        
        // Write data length (varint)
        varintBuf.copy(buffer, offset);
        offset += varintBuf.length;
        
        // Write data
        this.data.copy(buffer, offset);
        offset += this.data.length;
        
        // Write hash functions (uint32LE)
        buffer.writeUInt32LE(this.hashFuncs, offset);
        offset += 4;
        
        // Write nTweak (uint32LE)
        buffer.writeUInt32LE(this.nTweak, offset);
        offset += 4;
        
        // Write flags (byte)
        buffer[offset] = this.nFlags;
        
        return buffer;
    }

    private static rotateLeft32(x: number, r: number): number {
        return (x << r) | (x >>> (32 - r));
    }

    private static murmurHash3(data: Buffer, nTweak: number, hashNum: number, object: Uint8Array): number {
        let h1 = hashNum * 0xFBA4C795 + nTweak;
        const c1 = 0xcc9e2d51;
        const c2 = 0x1b873593;

        const numBlocks = Math.floor(object.length / 4) * 4;
        
        // Process 4-byte blocks
        for (let i = 0; i < numBlocks; i += 4) {
            let k1 = object[i] |
                (object[i + 1] << 8) |
                (object[i + 2] << 16) |
                (object[i + 3] << 24);
            
            k1 *= c1;
            k1 = BloomFilter.rotateLeft32(k1, 15);
            k1 *= c2;

            h1 ^= k1;
            h1 = BloomFilter.rotateLeft32(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }
        
        let k1 = 0;
        const tailStart = numBlocks;
        const remaining = object.length & 3;
        
        switch (remaining) {
            case 3:
                k1 ^= object[tailStart + 2] << 16;
            // Fall through
            case 2:
                k1 ^= object[tailStart + 1] << 8;
            // Fall through
            case 1:
                k1 ^= object[tailStart];
                k1 *= c1;
                k1 = BloomFilter.rotateLeft32(k1, 15);
                k1 *= c2;
                h1 ^= k1;
        }

        // Finalization
        h1 ^= object.length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        
        return (h1 >>> 0) % (data.length * 8);
    }

    contains(object: Uint8Array): boolean {
        for (let i = 0; i < this.hashFuncs; i++) {
            const bitIndex = BloomFilter.murmurHash3(this.data, this.nTweak, i, object);
            if (!this.checkBitLE(bitIndex)) {
                return false;
            }
        }
        return true;
    }

    insert(object: Uint8Array) {
        for (let i = 0; i < this.hashFuncs; i++) {
            const bitIndex = BloomFilter.murmurHash3(this.data, this.nTweak, i, object);
            this.setBitLE(bitIndex);
        }
    }

    private checkBitLE(bitIndex: number): boolean {
        const byteIndex = Math.floor(bitIndex / 8);
        const bit = bitIndex % 8;
        return (this.data[byteIndex] & (1 << bit)) !== 0;
    }

    private setBitLE(bitIndex: number) {
        const byteIndex = Math.floor(bitIndex / 8);
        const bit = bitIndex % 8;
        this.data[byteIndex] |= (1 << bit);
    }

    insertKey(key: ECKey) {
        // These methods don't exist on ECKey, need to implement differently
        // Placeholder until we fix ECKey implementation
        throw new Error("insertKey not implemented yet");
    }

    setMatchAll() {
        this.data = Buffer.from([0xff]);
    }

    merge(filter: BloomFilter) {
        if (!this.matchesAll() && !filter.matchesAll()) {
            if (filter.data.length !== this.data.length ||
                filter.hashFuncs !== this.hashFuncs ||
                filter.nTweak !== this.nTweak) {
                throw new Error("Filters must have the same size, hash function count, and nTweak");
            }
            
            for (let i = 0; i < this.data.length; i++) {
                this.data[i] |= filter.data[i];
            }
        } else {
            this.data = Buffer.from([0xff]);
        }
    }

    matchesAll(): boolean {
        for (let i = 0; i < this.data.length; i++) {
            if (this.data[i] !== 0xff) {
                return false;
            }
        }
        return true;
    }

    equals(other: any): boolean {
        if (this === other) return true;
        if (!(other instanceof BloomFilter)) return false;
        return this.data.equals(other.data) &&
            this.hashFuncs === other.hashFuncs &&
            this.nTweak === other.nTweak;
    }

    hashCode(): number {
        let hash = 17;
        // Simple hash calculation for buffer
        for (let i = 0; i < this.data.length; i++) {
            hash = 31 * hash + this.data[i];
        }
        hash = 31 * hash + this.hashFuncs;
        hash = 31 * hash + this.nTweak;
        return hash;
    }
}
