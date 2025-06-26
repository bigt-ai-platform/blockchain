// TypeScript translation of UTXOProviderException.java

export class UTXOProviderException extends Error {
    constructor(message?: string, cause?: Error) {
        if (cause) {
            super(message ? `${message}: ${cause.message}` : cause.message);
            (this as any).cause = cause;
        } else {
            super(message);
        }
        this.name = 'UTXOProviderException';
    }
}
