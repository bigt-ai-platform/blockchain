export class BlockStoreException extends Error {
    cause?: Error;

    constructor(message?: string | Error, cause?: Error) {
        if (message instanceof Error) {
            cause = message;
            message = undefined;
        }
        super(message);
        this.name = 'BlockStoreException';
        if (cause) this.cause = cause;
    }
}
