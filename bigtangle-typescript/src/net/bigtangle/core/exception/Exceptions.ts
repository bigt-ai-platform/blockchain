export * from './AddressFormatException';
export * from './WrongNetworkException';

export class ProtocolException extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'ProtocolException';
    }
}
