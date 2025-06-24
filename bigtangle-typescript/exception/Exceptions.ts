export class InsufficientMoneyException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "InsufficientMoneyException";
        Object.setPrototypeOf(this, InsufficientMoneyException.prototype);
    }
}

export class NoDataException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "NoDataException";
        Object.setPrototypeOf(this, NoDataException.prototype);
    }
}

export class NoTokenException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "NoTokenException";
        Object.setPrototypeOf(this, NoTokenException.prototype);
    }
}

export class OrderImpossibleException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "OrderImpossibleException";
        Object.setPrototypeOf(this, OrderImpossibleException.prototype);
    }
}

export class OrderWithRemainderException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "OrderWithRemainderException";
        Object.setPrototypeOf(this, OrderWithRemainderException.prototype);
    }
}

export class InvalidTransactionDataException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "InvalidTransactionDataException";
        Object.setPrototypeOf(this, InvalidTransactionDataException.prototype);
    }
}

export class ConnectException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "ConnectException";
        Object.setPrototypeOf(this, ConnectException.prototype);
    }
}

export class ProtocolException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "ProtocolException";
        Object.setPrototypeOf(this, ProtocolException.prototype);
    }
}
