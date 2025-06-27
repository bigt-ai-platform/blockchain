export class UnreadableWalletException extends Error {
    constructor(message?: string, cause?: Error) {
        super(message);
        this.name = "UnreadableWalletException";
        Object.setPrototypeOf(this, UnreadableWalletException.prototype);
        if (cause) {
            this.cause = cause;
        }
    }
    public cause?: Error;

    public static BadPassword = class BadPassword extends UnreadableWalletException {
        constructor() {
            super("Password incorrect");
            this.name = "BadPassword";
            Object.setPrototypeOf(this, BadPassword.prototype);
        }
    };

    public static FutureVersion = class FutureVersion extends UnreadableWalletException {
        constructor() {
            super("Unknown wallet version from the future.");
            this.name = "FutureVersion";
            Object.setPrototypeOf(this, FutureVersion.prototype);
        }
    };

    public static WrongNetwork = class WrongNetwork extends UnreadableWalletException {
        constructor() {
            super("Mismatched network ID");
            this.name = "WrongNetwork";
            Object.setPrototypeOf(this, WrongNetwork.prototype);
        }
    };
}
