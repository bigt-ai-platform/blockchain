export class PeerException extends Error {
    constructor(message?: string, cause?: Error) {
        super(message);
        this.name = "PeerException";
        Object.setPrototypeOf(this, PeerException.prototype);
        if (cause) {
            this.cause = cause;
        }
    }
    public cause?: Error;
}