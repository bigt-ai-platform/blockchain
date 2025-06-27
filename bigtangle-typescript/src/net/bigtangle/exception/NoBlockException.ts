export class NoBlockException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "NoBlockException";
        Object.setPrototypeOf(this, NoBlockException.prototype);
    }
}