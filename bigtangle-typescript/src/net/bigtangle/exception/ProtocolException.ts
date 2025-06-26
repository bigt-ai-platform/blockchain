// TypeScript translation of ProtocolException.java
import { VerificationException } from './VerificationException';

export class ProtocolException extends VerificationException {
    constructor(messageOrError?: string | Error, cause?: Error) {
        if (messageOrError instanceof Error) {
            super(messageOrError.message, messageOrError);
        } else {
            super(messageOrError, cause);
        }
        this.name = 'ProtocolException';
    }
}
