export class VarInt {
    private value: number;

    constructor(value: number) {
        this.value = value;
    }

    encode(): Uint8Array {
        if (this.value < 0xFD) {
            return new Uint8Array([this.value]);
        } else if (this.value <= 0xFFFF) {
            const buf = new Uint8Array(3);
            buf[0] = 0xFD;
            buf[1] = this.value & 0xFF;
            buf[2] = (this.value >> 8) & 0xFF;
            return buf;
        } else if (this.value <= 0xFFFFFFFF) {
            const buf = new Uint8Array(5);
            buf[0] = 0xFE;
            buf[1] = this.value & 0xFF;
            buf[2] = (this.value >> 8) & 0xFF;
            buf[3] = (this.value >> 16) & 0xFF;
            buf[4] = (this.value >> 24) & 0xFF;
            return buf;
        } else {
            // For values larger than 2^32-1, a BigInt would be needed in JavaScript.
            // For now, throw an error or handle as appropriate for the context.
            throw new Error("VarInt value too large for standard JavaScript number.");
        }
    }

    static sizeOf(value: number): number {
        if (value < 0xFD) {
            return 1;
        } else if (value <= 0xFFFF) {
            return 3;
        } else if (value <= 0xFFFFFFFF) {
            return 5;
        } else {
            return 9; // For 64-bit varint
        }
    }
}
