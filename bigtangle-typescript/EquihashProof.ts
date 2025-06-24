export class EquihashProof {
    private solution: Uint8Array;

    constructor(solution: Uint8Array) {
        this.solution = solution;
    }

    static BYTE_LENGTH = 1344; // Example length, adjust as per actual Equihash proof size

    static from(bytes: Uint8Array): EquihashProof {
        return new EquihashProof(bytes);
    }

    static getDummy(): EquihashProof {
        return new EquihashProof(new Uint8Array(EquihashProof.BYTE_LENGTH));
    }

    serialize(): Uint8Array {
        return this.solution;
    }
}
