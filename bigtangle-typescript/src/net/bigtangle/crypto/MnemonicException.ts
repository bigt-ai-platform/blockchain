export class MnemonicException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "MnemonicException";
        Object.setPrototypeOf(this, MnemonicException.prototype);
    }

    static MnemonicLengthException = class MnemonicLengthException extends MnemonicException {
        constructor(message?: string) {
            super(message);
            this.name = "MnemonicLengthException";
            Object.setPrototypeOf(this, MnemonicLengthException.prototype);
        }
    };

    static MnemonicWordException = class MnemonicWordException extends MnemonicException {
        constructor(word: string) {
            super(`Word not found in wordlist: ${word}`);
            this.name = "MnemonicWordException";
            Object.setPrototypeOf(this, MnemonicWordException.prototype);
        }
    };

    static MnemonicChecksumException = class MnemonicChecksumException extends MnemonicException {
        constructor() {
            super("Checksum mismatch");
            this.name = "MnemonicChecksumException";
            Object.setPrototypeOf(this, MnemonicChecksumException.prototype);
        }
    };
}
