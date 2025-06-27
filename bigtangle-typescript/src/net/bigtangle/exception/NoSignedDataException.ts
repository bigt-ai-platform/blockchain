export class NoSignedDataException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "NoSignedDataException";
        Object.setPrototypeOf(this, NoSignedDataException.prototype);
    }
}