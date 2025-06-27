export class NoTokenException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "NoTokenException";
        Object.setPrototypeOf(this, NoTokenException.prototype);
    }
}