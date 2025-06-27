export class AddressFormatException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "AddressFormatException";
        Object.setPrototypeOf(this, AddressFormatException.prototype);
    }
}