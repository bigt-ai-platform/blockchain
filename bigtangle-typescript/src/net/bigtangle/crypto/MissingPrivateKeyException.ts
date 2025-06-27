export class MissingPrivateKeyException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "MissingPrivateKeyException";
        Object.setPrototypeOf(this, MissingPrivateKeyException.prototype);
    }
}
