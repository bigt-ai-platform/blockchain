export class VerificationException extends Error {
    constructor(message?: string, cause?: Error) {
        super(message);
        this.name = "VerificationException";
        Object.setPrototypeOf(this, VerificationException.prototype);
        if (cause) {
            this.cause = cause;
        }
    }
    public cause?: Error;

    public static InfeasiblePrototypeException = class InfeasiblePrototypeException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "InfeasiblePrototypeException";
            Object.setPrototypeOf(this, InfeasiblePrototypeException.prototype);
        }
    };

    public static LargerThanMaxBlockSize = class LargerThanMaxBlockSize extends VerificationException {
        constructor() {
            super("Message larger than MAX_BLOCK_SIZE");
            this.name = "LargerThanMaxBlockSize";
            Object.setPrototypeOf(this, LargerThanMaxBlockSize.prototype);
        }
    };

    public static DuplicatedOutPoint = class DuplicatedOutPoint extends VerificationException {
        constructor() {
            super("Duplicated outpoint");
            this.name = "DuplicatedOutPoint";
            Object.setPrototypeOf(this, DuplicatedOutPoint.prototype);
        }
    };

    public static NegativeValueOutput = class NegativeValueOutput extends VerificationException {
        constructor() {
            super("Transaction output negative");
            this.name = "NegativeValueOutput";
            Object.setPrototypeOf(this, NegativeValueOutput.prototype);
        }
    };

    public static ExcessiveValue = class ExcessiveValue extends VerificationException {
        constructor() {
            super("Total transaction output value greater than possible");
            this.name = "ExcessiveValue";
            Object.setPrototypeOf(this, ExcessiveValue.prototype);
        }
    };

    public static CoinbaseScriptSizeOutOfRange = class CoinbaseScriptSizeOutOfRange extends VerificationException {
        constructor() {
            super("Coinbase script size out of range");
            this.name = "CoinbaseScriptSizeOutOfRange";
            Object.setPrototypeOf(this, CoinbaseScriptSizeOutOfRange.prototype);
        }
    };

    public static BlockVersionOutOfDate = class BlockVersionOutOfDate extends VerificationException {
        constructor(version: number) {
            super(`Block version #${version} is outdated.`);
            this.name = "BlockVersionOutOfDate";
            Object.setPrototypeOf(this, BlockVersionOutOfDate.prototype);
        }
    };

    public static UnexpectedCoinbaseInput = class UnexpectedCoinbaseInput extends VerificationException {
        constructor() {
            super("Coinbase input as input in non-coinbase transaction");
            this.name = "UnexpectedCoinbaseInput";
            Object.setPrototypeOf(this, UnexpectedCoinbaseInput.prototype);
        }
    };

    public static GenericInvalidityException = class GenericInvalidityException extends VerificationException {
        constructor() {
            super("Shouldn't happen. This block is invalid.");
            this.name = "GenericInvalidityException";
            Object.setPrototypeOf(this, GenericInvalidityException.prototype);
        }
    };

    public static GenesisBlockDisallowedException = class GenesisBlockDisallowedException extends VerificationException {
        constructor() {
            super("Genesis blocks not allowed");
            this.name = "GenesisBlockDisallowedException";
            Object.setPrototypeOf(this, GenesisBlockDisallowedException.prototype);
        }
    };

    public static TimeReversionException = class TimeReversionException extends VerificationException {
        constructor() {
            super("Timestamps are reversing!");
            this.name = "TimeReversionException";
            Object.setPrototypeOf(this, TimeReversionException.prototype);
        }
    };

    public static DifficultyConsensusInheritanceException = class DifficultyConsensusInheritanceException extends VerificationException {
        constructor() {
            super("Difficulty and consensus not inherited correctly");
            this.name = "DifficultyConsensusInheritanceException";
            Object.setPrototypeOf(this, DifficultyConsensusInheritanceException.prototype);
        }
    };

    public static IncorrectTransactionCountException = class IncorrectTransactionCountException extends VerificationException {
        constructor() {
            super("Incorrect tx count");
            this.name = "IncorrectTransactionCountException";
            Object.setPrototypeOf(this, IncorrectTransactionCountException.prototype);
        }
    };

    public static TransactionInputsDisallowedException = class TransactionInputsDisallowedException extends VerificationException {
        constructor() {
            super("TX has inputs");
            this.name = "TransactionInputsDisallowedException";
            Object.setPrototypeOf(this, TransactionInputsDisallowedException.prototype);
        }
    };

    public static TransactionOutputsDisallowedException = class TransactionOutputsDisallowedException extends VerificationException {
        constructor() {
            super("TX has outputs");
            this.name = "TransactionOutputsDisallowedException";
            Object.setPrototypeOf(this, TransactionOutputsDisallowedException.prototype);
        }
    };

    public static MalformedTransactionDataException = class MalformedTransactionDataException extends VerificationException {
        constructor() {
            super("Incorrect data format");
            this.name = "MalformedTransactionDataException";
            Object.setPrototypeOf(this, MalformedTransactionDataException.prototype);
        }
    };

    public static MissingDependencyException = class MissingDependencyException extends VerificationException {
        constructor() {
            super("No dependency defined");
            this.name = "MissingDependencyException";
            Object.setPrototypeOf(this, MissingDependencyException.prototype);
        }
    };

    public static UnsolidException = class UnsolidException extends VerificationException {
        constructor() {
            super("Not solid. Server disallows unsolid blocks.");
            this.name = "UnsolidException";
            Object.setPrototypeOf(this, UnsolidException.prototype);
        }
    };

    public static InvalidDependencyException = class InvalidDependencyException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "InvalidDependencyException";
            Object.setPrototypeOf(this, InvalidDependencyException.prototype);
        }
    };

    public static InvalidTransactionDataException = class InvalidTransactionDataException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "InvalidTransactionDataException";
            Object.setPrototypeOf(this, InvalidTransactionDataException.prototype);
        }
    };

    public static NotCoinbaseException = class NotCoinbaseException extends VerificationException {
        constructor() {
            super("TX is not coinbase! ");
            this.name = "NotCoinbaseException";
            Object.setPrototypeOf(this, NotCoinbaseException.prototype);
        }
    };

    public static CoinbaseDisallowedException = class CoinbaseDisallowedException extends VerificationException {
        constructor() {
            super("TX is coinbase! ");
            this.name = "CoinbaseDisallowedException";
            Object.setPrototypeOf(this, CoinbaseDisallowedException.prototype);
        }
    };

    public static MissingTransactionDataException = class MissingTransactionDataException extends VerificationException {
        constructor() {
            super("Missing required transaction data! ");
            this.name = "MissingTransactionDataException";
            Object.setPrototypeOf(this, MissingTransactionDataException.prototype);
        }
    };

    public static InvalidTokenOutputException = class InvalidTokenOutputException extends VerificationException {
        constructor() {
            super("Invalid tokens were generated");
            this.name = "InvalidTokenOutputException";
            Object.setPrototypeOf(this, InvalidTokenOutputException.prototype);
        }
    };

    public static PreviousTokenDisallowsException = class PreviousTokenDisallowsException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "PreviousTokenDisallowsException";
            Object.setPrototypeOf(this, PreviousTokenDisallowsException.prototype);
        }
    };

    public static MissingSignatureException = class MissingSignatureException extends VerificationException {
        constructor() {
            super("Signature missing");
            this.name = "MissingSignatureException";
            Object.setPrototypeOf(this, MissingSignatureException.prototype);
        }
    };

    public static InvalidSignatureException = class InvalidSignatureException extends VerificationException {
        constructor() {
            super("Some signatures are not valid here");
            this.name = "InvalidSignatureException";
            Object.setPrototypeOf(this, InvalidSignatureException.prototype);
        }
    };

    public static InsufficientSignaturesException = class InsufficientSignaturesException extends VerificationException {
        constructor() {
            super("Not enough signatures");
            this.name = "InsufficientSignaturesException";
            Object.setPrototypeOf(this, InsufficientSignaturesException.prototype);
        }
    };

    public static SigOpsException = class SigOpsException extends VerificationException {
        constructor() {
            super("Block had too many Signature Operations");
            this.name = "SigOpsException";
            Object.setPrototypeOf(this, SigOpsException.prototype);
        }
    };

    public static MerkleRootMismatchException = class MerkleRootMismatchException extends VerificationException {
        constructor() {
            super("Merkle hashes do not match");
            this.name = "MerkleRootMismatchException";
            Object.setPrototypeOf(this, MerkleRootMismatchException.prototype);
        }
    };

    public static ProofOfWorkException = class ProofOfWorkException extends VerificationException {
        constructor() {
            super("Hash is higher than target");
            this.name = "ProofOfWorkException";
            Object.setPrototypeOf(this, ProofOfWorkException.prototype);
        }
    };

    public static TimeTravelerException = class TimeTravelerException extends VerificationException {
        constructor() {
            super("Block too far in future");
            this.name = "TimeTravelerException";
            Object.setPrototypeOf(this, TimeTravelerException.prototype);
        }
    };
    
    public static InvalidTransactionException = class InvalidTransactionException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "InvalidTransactionException";
            Object.setPrototypeOf(this, InvalidTransactionException.prototype);
        }
    };
    
    public static DifficultyTargetException = class DifficultyTargetException extends VerificationException {
        constructor() {
            super("Difficulty target is bad");
            this.name = "DifficultyTargetException";
            Object.setPrototypeOf(this, DifficultyTargetException.prototype);
        }
    };
    
    public static InvalidOrderException = class InvalidOrderException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "InvalidOrderException";
            Object.setPrototypeOf(this, InvalidOrderException.prototype);
        }
    };
    public static CutoffException = class CutoffException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "CutoffException";
            Object.setPrototypeOf(this, CutoffException.prototype);
        }
    };
    public static ConflictPossibleException = class ConflictPossibleException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "ConflictPossibleException";
            Object.setPrototypeOf(this, ConflictPossibleException.prototype);
        }
    };
    public static OrderImpossibleException = class OrderImpossibleException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "OrderImpossibleException";
            Object.setPrototypeOf(this, OrderImpossibleException.prototype);
        }
    };
    
    public static OrderWithRemainderException = class OrderWithRemainderException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "OrderWithRemainderException";
            Object.setPrototypeOf(this, OrderWithRemainderException.prototype);
        }
    };
    
    public static NoFeeException = class NoFeeException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "NoFeeException";
            Object.setPrototypeOf(this, NoFeeException.prototype);
        }
    };
    public static InputOutputMatchException = class InputOutputMatchException extends VerificationException {
        constructor(message: string) {
            super(message);
            this.name = "InputOutputMatchException";
            Object.setPrototypeOf(this, InputOutputMatchException.prototype);
        }
    };
}