export class AddressFormatException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = 'AddressFormatException';
    }
}
