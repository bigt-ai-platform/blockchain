import { VerificationException } from './VerificationException';

export class ProtocolException extends VerificationException {
    constructor(message?: string, cause?: Error) {
        super(message, cause);
        this.name = "ProtocolException";
        Object.setPrototypeOf(this, ProtocolException.prototype);
    }
}