export class UTXOProviderException extends Error {
    constructor(message?: string, cause?: Error) {
        super(message);
        this.name = "UTXOProviderException";
        Object.setPrototypeOf(this, UTXOProviderException.prototype);
        if (cause) {
            this.cause = cause;
        }
    }
    public cause?: Error;
}