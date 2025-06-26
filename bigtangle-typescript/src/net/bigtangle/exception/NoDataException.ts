// TypeScript translation of NoDataException.java

export class NoDataException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = 'NoDataException';
    }
}
