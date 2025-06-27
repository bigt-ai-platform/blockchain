export class InsufficientMoneyException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "InsufficientMoneyException";
        Object.setPrototypeOf(this, InsufficientMoneyException.prototype);
    }
}