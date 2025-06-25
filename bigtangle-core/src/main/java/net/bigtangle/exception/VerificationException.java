/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bigtangle.exception;

@SuppressWarnings("serial")
public class VerificationException extends RuntimeException {
    public VerificationException(String msg) {
        super(msg);
    }

    public VerificationException(Exception e) {
        super(e);
    }

    public VerificationException(String msg, Throwable t) {
        super(msg, t);
    }

    public static class InfeasiblePrototypeException extends VerificationException {
        public InfeasiblePrototypeException(String string) {
            super(string);
        }
    }

    public static class LargerThanMaxBlockSize extends VerificationException {
        public LargerThanMaxBlockSize() {
            super("Message larger than MAX_BLOCK_SIZE");
        }
    }

    public static class DuplicatedOutPoint extends VerificationException {
        public DuplicatedOutPoint() {
            super("Duplicated outpoint");
        }
    }

    public static class NegativeValueOutput extends VerificationException {
        public NegativeValueOutput() {
            super("Transaction output negative");
        }
    }

    public static class ExcessiveValue extends VerificationException {
        public ExcessiveValue() {
            super("Total transaction output value greater than possible");
        }
    }


    public static class CoinbaseScriptSizeOutOfRange extends VerificationException {
        public CoinbaseScriptSizeOutOfRange() {
            super("Coinbase script size out of range");
        }
    }

    public static class BlockVersionOutOfDate extends VerificationException {
        public BlockVersionOutOfDate(final long version) {
            super("Block version #"
                + version + " is outdated.");
        }
    }

    public static class UnexpectedCoinbaseInput extends VerificationException {
        public UnexpectedCoinbaseInput() {
            super("Coinbase input as input in non-coinbase transaction");
        }
    }

    public static class GenericInvalidityException extends VerificationException {
        public GenericInvalidityException() {
            super("Shouldn't happen. This block is invalid.");
        }
    }

    public static class GenesisBlockDisallowedException extends VerificationException {
        public GenesisBlockDisallowedException() {
            super("Genesis blocks not allowed");
        }
    }

    public static class TimeReversionException extends VerificationException {
        public TimeReversionException() {
            super("Timestamps are reversing!");
        }
    }

    public static class DifficultyConsensusInheritanceException extends VerificationException {
        public DifficultyConsensusInheritanceException() {
            super("Difficulty and consensus not inherited correctly");
        }
    }

    public static class IncorrectTransactionCountException extends VerificationException {
        public IncorrectTransactionCountException() {
            super("Incorrect tx count");
        }
    }

    public static class TransactionInputsDisallowedException extends VerificationException {
        public TransactionInputsDisallowedException() {
            super("TX has inputs");
        }
    }

    public static class TransactionOutputsDisallowedException extends VerificationException {
        public TransactionOutputsDisallowedException() {
            super("TX has outputs");
        }
    }

    public static class MalformedTransactionDataException extends VerificationException {
        public MalformedTransactionDataException() {
            super("Incorrect data format");
        }
    }

    public static class MissingDependencyException extends VerificationException {
        public MissingDependencyException() {
            super("No dependency defined");
        }
    }

    public static class UnsolidException extends VerificationException {
        public UnsolidException() {
            super("Not solid. Server disallows unsolid blocks.");
        }
    }

    public static class InvalidDependencyException extends VerificationException {
        public InvalidDependencyException(String msg) {
            super(msg);
        }
    }

    public static class InvalidTransactionDataException extends VerificationException {
        public InvalidTransactionDataException(String msg) {
            super(msg);
        }
    }

    public static class NotCoinbaseException extends VerificationException {
        public NotCoinbaseException() {
            super("TX is not coinbase! ");
        }
    }

    public static class CoinbaseDisallowedException extends VerificationException {
        public CoinbaseDisallowedException() {
            super("TX is coinbase! ");
        }
    }

    public static class MissingTransactionDataException extends VerificationException {
        public MissingTransactionDataException() {
            super("Missing required transaction data! ");
        }
    }

    public static class InvalidTokenOutputException extends VerificationException {
        public InvalidTokenOutputException() {
            super("Invalid tokens were generated");
        }
    }

    public static class PreviousTokenDisallowsException extends VerificationException {
        public PreviousTokenDisallowsException(String msg) {
            super(msg);
        }
    }

    public static class MissingSignatureException extends VerificationException {
        public MissingSignatureException() {
            super("Signature missing");
        }
    }

    public static class InvalidSignatureException extends VerificationException {
        public InvalidSignatureException() {
            super("Some signatures are not valid here");
        }
    }

    public static class InsufficientSignaturesException extends VerificationException {
        public InsufficientSignaturesException() {
            super("Not enough signatures");
        }
    }

    public static class SigOpsException extends VerificationException {
        public SigOpsException() {
            super("Block had too many Signature Operations");
        }
    }

    public static class MerkleRootMismatchException extends VerificationException {
        public MerkleRootMismatchException() {
            super("Merkle hashes do not match");
        }
    }

    public static class ProofOfWorkException extends VerificationException {
        public ProofOfWorkException() {
            super("Hash is higher than target");
        }
    }

    public static class TimeTravelerException extends VerificationException {
        public TimeTravelerException() {
            super("Block too far in future");
        }
    }
    
    public static class InvalidTransactionException extends VerificationException {
        public InvalidTransactionException(String msg) {
            super(msg);
        }
    }
    
    public static class DifficultyTargetException extends VerificationException {
        public DifficultyTargetException() {
            super("Difficulty target is bad");
        }
    }
    
    public static class InvalidOrderException extends VerificationException {
        public InvalidOrderException(String msg) {
            super(msg);
        }
    }
    public static class CutoffException extends VerificationException {
        public CutoffException(String msg) {
            super(msg);
        }
    }
    public static class ConflictPossibleException extends VerificationException {
        public ConflictPossibleException(String msg) {
            super(msg);
        }
    }
    public static class OrderImpossibleException extends VerificationException {
        public OrderImpossibleException(String msg) {
            super(msg);
        }
    }
    
    public static class OrderWithRemainderException extends VerificationException {
        public OrderWithRemainderException(String msg) {
            super(msg);
        }
    }
    
    public static class NoFeeException extends VerificationException {
        public NoFeeException(String msg) {
            super(msg);
        }
    }
    public static class InputOutputMatchException extends VerificationException {
        public InputOutputMatchException(String msg) {
            super(msg);
        }
    }
}
