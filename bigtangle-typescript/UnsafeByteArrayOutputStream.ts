import { Utils } from './Utils'; // Assuming Utils.ts exists and has copyOf

/**
 * An unsynchronized implementation of ByteArrayOutputStream that will return the backing byte array if its length == size().
 * This avoids unneeded array copy where the BOS is simply being used to extract a byte array of known length from a
 * 'serialized to stream' method.
 * <p/>
 * Unless the final length can be accurately predicted the only performance this will yield is due to unsynchronized
 * methods.
 *
 * @author git
 */
export class UnsafeByteArrayOutputStream {
    protected buf: Uint8Array;
    protected count: number;

    constructor(size: number = 32) {
        this.buf = new Uint8Array(size);
        this.count = 0;
    }

    /**
     * Writes the specified byte to this byte array output stream.
     *
     * @param b the byte to be written.
     */
    write(b: number): void {
        const newcount = this.count + 1;
        if (newcount > this.buf.length) {
            this.buf = Utils.copyOf(this.buf, Math.max(this.buf.length << 1, newcount));
        }
        this.buf[this.count] = b & 0xFF; // Ensure byte value
        this.count = newcount;
    }

    /**
     * Writes `len` bytes from the specified byte array
     * starting at offset `off` to this byte array output stream.
     *
     * @param b   the data.
     * @param off the start offset in the data.
     * @param len the number of bytes to write.
     */
    writeBytes(b: Uint8Array, off: number, len: number): void {
        if ((off < 0) || (off > b.length) || (len < 0) ||
            ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException(); // Assuming IndexOutOfBoundsException exists
        } else if (len === 0) {
            return;
        }
        const newcount = this.count + len;
        if (newcount > this.buf.length) {
            this.buf = Utils.copyOf(this.buf, Math.max(this.buf.length << 1, newcount));
        }
        this.buf.set(b.subarray(off, off + len), this.count);
        this.count = newcount;
    }

    /**
     * Writes the complete contents of this byte array output stream to
     * the specified output stream argument, as if by calling the output
     * stream's write method using `out.write(buf, 0, count)`.
     *
     * @param out the output stream to which to write the data.
     */
    writeTo(out: any): void { // Assuming 'any' for OutputStream equivalent
        out.write(this.buf.subarray(0, this.count));
    }

    /**
     * Resets the `count` field of this byte array output
     * stream to zero, so that all currently accumulated output in the
     * output stream is discarded. The output stream can be used again,
     * reusing the already allocated buffer space.
     */
    reset(): void {
        this.count = 0;
    }

    /**
     * Creates a newly allocated byte array. Its size is the current
     * size of this output stream and the valid contents of the buffer
     * have been copied into it.
     *
     * @return the current contents of this output stream, as a byte array.
     */
    toByteArray(): Uint8Array {
        return this.count === this.buf.length ? this.buf : Utils.copyOf(this.buf, this.count);
    }

    /**
     * Returns the current size of the buffer.
     *
     * @return the value of the `count` field, which is the number
     *         of valid bytes in this output stream.
     */
    size(): number {
        return this.count;
    }
}

// Placeholder for IndexOutOfBoundsException
class IndexOutOfBoundsException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "IndexOutOfBoundsException";
        Object.setPrototypeOf(this, IndexOutOfBoundsException.prototype);
    }
}
