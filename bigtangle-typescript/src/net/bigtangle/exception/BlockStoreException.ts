export class BlockStoreException extends Error {
    constructor(message?: string, cause?: Error) {
        super(message);
        this.name = "BlockStoreException";
        Object.setPrototypeOf(this, BlockStoreException.prototype);
        if (cause) {
            this.cause = cause;
        }
    }
    public cause?: Error;
}