export class NoDataException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "NoDataException";
        Object.setPrototypeOf(this, NoDataException.prototype);
    }
}