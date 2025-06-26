// TypeScript translation of PeerException.java

export class PeerException extends Error {
    constructor(messageOrError?: string | Error, cause?: Error) {
        if (messageOrError instanceof Error) {
            super(messageOrError.message);
            (this as any).cause = messageOrError;
        } else {
            super(messageOrError);
            if (cause) (this as any).cause = cause;
        }
        this.name = 'PeerException';
    }
}
