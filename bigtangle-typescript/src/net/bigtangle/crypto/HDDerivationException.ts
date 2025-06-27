export class HDDerivationException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "HDDerivationException";
        Object.setPrototypeOf(this, HDDerivationException.prototype);
    }
}