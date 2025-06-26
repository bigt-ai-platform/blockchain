// TypeScript translation of ScriptException.java
import { VerificationException } from './VerificationException';

export class ScriptException extends VerificationException {
    constructor(message?: string, cause?: Error) {
        super(message, cause);
        this.name = 'ScriptException';
    }
}
