/**
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
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

/*
 * Authors: Jim Burton, Miron Cuperman, Andreas Schildbach
 */

/* Notes:
 * - This file is a TypeScript representation of the wallet.proto schema.
 * - Endianness: All byte arrays that represent numbers (such as hashes and private keys) are Big Endian.
 * - `Uint8Array` is used for the `bytes` type.
 * - 64-bit integer types (`int64`, `uint64`) are represented as `number`. Be aware that this can lead to
 *   loss of precision for values outside JavaScript's `Number.MAX_SAFE_INTEGER` range.
 *   For full precision, consider using `bigint` or a string representation.
 */

export interface PeerAddress {
  ip_address: Uint8Array;
  port: number;
  /** Note: 64-bit integers can exceed JavaScript's safe integer range. */
  services: number;
}

export interface EncryptedData {
  /** The initialisation vector for the AES encryption (16 bytes) */
  initialisation_vector: Uint8Array;
  /** The encrypted private key */
  encrypted_private_key: Uint8Array;
}

/**
 * Data attached to a Key message that defines the data needed by the BIP32 deterministic key hierarchy algorithm.
 */
export interface DeterministicKey {
  /**
   * Random data that allows us to extend a key. Without this, we can't figure out the next key in the chain and
   * should just treat it as a regular ORIGINAL type key.
   */
  chain_code: Uint8Array;

  /**
   * The path through the key tree. Each number is encoded in the standard form: high bit set for private derivation
   * and high bit unset for public derivation.
   */
  path: number[];

  /**
   * How many children of this key have been issued, that is, given to the user when they requested a fresh key?
   * For the parents of keys being handed out, this is always less than the true number of children: the difference is
   * called the lookahead zone. These keys are put into Bloom filters so we can spot transactions made by clones of
   * this wallet - for instance when restoring from backup or if the seed was shared between devices.
   *
   * If this field is missing it means we're not issuing subkeys of this key to users.
   */
  issued_subkeys?: number;
  lookahead_size?: number;

  /**
   * Flag indicating that this key is a root of a following chain. This chain is following the next non-following chain.
   * Following/followed chains concept is used for married keychains, where the set of keys combined together to produce
   * a single P2SH multisignature address
   */
  isFollowing?: boolean;

  /**
   * Number of signatures required to spend. This field is needed only for married keychains to reconstruct KeyChain
   * and represents the N value from N-of-M CHECKMULTISIG script. For regular single keychains it will always be 1.
   */
  sigsRequiredToSpend?: number; // Default: 1
}

export enum KeyType {
  /** Unencrypted - Original bitcoin secp256k1 curve */
  ORIGINAL = 1,

  /** Encrypted with Scrypt and AES - Original bitcoin secp256k1 curve */
  ENCRYPTED_SCRYPT_AES = 2,

  /**
   * Not really a key, but rather contains the mnemonic phrase for a deterministic key hierarchy in the private_key field.
   * The label and public_key fields are missing. Creation timestamp will exist.
   */
  DETERMINISTIC_MNEMONIC = 3,

  /**
   * A key that was derived deterministically. Note that the root seed that created it may NOT be present in the
   * wallet, for the case of watching wallets. A deterministic key may or may not have the private key bytes present.
   * However the public key bytes and the deterministic_key field are guaranteed to exist. In a wallet where there
   * is a path from this key up to a key that has (possibly encrypted) private bytes, it's expected that the private
   * key can be rederived on the fly.
   */
  DETERMINISTIC_KEY = 4,
}

/**
 * A key used to control Bitcoin spending.
 *
 * Either the private key, the public key or both may be present.  It is recommended that
 * if the private key is provided that the public key is provided too because deriving it is slow.
 *
 * If only the public key is provided, the key can only be used to watch the blockchain and verify
 * transactions, and not for spending.
 */
export interface Key {
  type: KeyType;

  /**
   * Either the private EC key bytes (without any ASN.1 wrapping), or the deterministic root seed.
   * If the secret is encrypted, or this is a "watching entry" then this is missing.
   */
  secret_bytes?: Uint8Array;

  /** If the secret data is encrypted, then secret_bytes is missing and this field is set. */
  encrypted_data?: EncryptedData;

  /**
   * The public EC key derived from the private key. We allow both to be stored to avoid mobile clients having to
   * do lots of slow EC math on startup. For DETERMINISTIC_MNEMONIC entries this is missing.
   */
  public_key?: Uint8Array;

  /** User-provided label associated with the key. */
  label?: string;

  /**
   * Timestamp stored as millis since epoch. Useful for skipping block bodies before this point. The reason it's
   * optional is that keys derived from a parent don't have this data.
   * Note: 64-bit integers can exceed JavaScript's safe integer range.
   */
  creation_timestamp?: number;

  deterministic_key?: DeterministicKey;

  /**
   * The seed for a deterministic key hierarchy.  Derived from the mnemonic,
   * but cached here for quick startup.  Only applicable to a DETERMINISTIC_MNEMONIC key entry.
   */
  deterministic_seed?: Uint8Array;

  /** Encrypted version of the seed */
  encrypted_deterministic_seed?: EncryptedData;
}

export interface Script {
  program: Uint8Array;

  /**
   * Timestamp stored as millis since epoch. Useful for skipping block bodies before this point
   * when watching for scripts on the blockchain.
   * Note: 64-bit integers can exceed JavaScript's safe integer range.
   */
  creation_timestamp: number;
}

export interface TransactionInput {
  /** Hash of the transaction this input is using. */
  transaction_out_point_hash: Uint8Array;
  /** Index of transaction output used by this input. */
  transaction_out_point_index: number;
  /** Script that contains the signatures/pubkeys. */
  script_bytes: Uint8Array;
  /** Sequence number. */
  sequence?: number;
  /**
   * Value of connected output, if known.
   * Note: 64-bit integers can exceed JavaScript's safe integer range.
   */
  value?: number;
}

export interface TransactionOutput {
  /** Note: 64-bit integers can exceed JavaScript's safe integer range. */
  value: number;
  /** Note: 64-bit integers can exceed JavaScript's safe integer range. */
  tokenid: number;
  /** script of transaction output */
  script_bytes: Uint8Array;
  /** If spent, the hash of the transaction doing the spend. */
  spent_by_transaction_hash?: Uint8Array;
  /** If spent, the index of the transaction input of the transaction doing the spend. */
  spent_by_transaction_index?: number;
}

export enum TransactionConfidenceType {
  // See TransactionConfidence.java for a more thorough explanation of these types.
  UNKNOWN = 0,
  BUILDING = 1, // In best chain.  If and only if appeared_at_height is present.
  PENDING = 2, // Unconfirmed and sitting in the networks memory pools, waiting to be included in the chain.
  NOT_IN_BEST_CHAIN = 3, // Deprecated: equivalent to PENDING.
  DEAD = 4, // Either if overriding_transaction is present or transaction is dead coinbase.
  IN_CONFLICT = 5, // There is another transaction spending one of this transaction inputs.
}

export enum TransactionConfidenceSource {
  SOURCE_UNKNOWN = 0, // We don't know where it came from, or this is a wallet from the future.
  SOURCE_NETWORK = 1, // We received it from a network broadcast. This is the normal way to get payments.
  SOURCE_SELF = 2, // We made it ourselves, so we know it should be valid.
}

/**
 * A description of the confidence we have that a transaction cannot be reversed in the future.
 *
 * Parsing should be lenient, since this could change for different applications yet we should
 * maintain backward compatibility.
 */
export interface TransactionConfidence {
  /** This is optional in case we add confidence types to prevent parse errors - backwards compatible. */
  type?: TransactionConfidenceType;

  /** If type == BUILDING then this is the chain height at which the transaction was included. */
  appeared_at_height?: number;

  /**
   * If set, hash of the transaction that double spent this one into oblivion. A transaction can be double spent by
   * multiple transactions in the case of several inputs being re-spent by several transactions but we don't
   * bother to track them all, just the first. This only makes sense if type = DEAD.
   */
  overriding_transaction?: Uint8Array;

  /**
   * If type == BUILDING then this is the depth of the transaction in the blockchain.
   * Zero confirmations: depth = 0, one confirmation: depth = 1 etc.
   */
  depth?: number;

  broadcast_by: PeerAddress[];

  /**
   * Millis since epoch the transaction was last announced to us.
   * Note: 64-bit integers can exceed JavaScript's safe integer range.
   */
  last_broadcasted_at?: number;

  /** Where did we get this transaction from? Knowing the source may help us to risk analyze pending transactions. */
  source?: TransactionConfidenceSource;
}

/**
 * This is a bitfield oriented enum, with the following bits:
 *
 * bit 0 - spent
 * bit 1 - appears in alt chain
 * bit 2 - appears in best chain
 * bit 3 - double-spent
 * bit 4 - pending (we would like the tx to go into the best chain)
 *
 * Not all combinations are interesting, just the ones actually used in the enum.
 */
export enum TransactionPool {
  UNSPENT = 4, // In best chain, not all outputs spent
  SPENT = 5, // In best chain, all outputs spent
  INACTIVE = 2, // In non-best chain, not our transaction
  DEAD = 10, // Double-spent by a transaction in the best chain
  PENDING = 16, // Our transaction, not in any chain
  PENDING_INACTIVE = 18, // In non-best chain, our transaction
}

export enum TransactionPurpose {
  // Old wallets or the purpose genuinely is a mystery (e.g. imported from some external source).
  UNKNOWN = 0,
  // Created in response to a user request for payment. This is the normal case.
  USER_PAYMENT = 1,
  // Created automatically to move money from rotated keys.
  KEY_ROTATION = 2,
  // Stuff used by Lighthouse.
  ASSURANCE_CONTRACT_CLAIM = 3,
  ASSURANCE_CONTRACT_PLEDGE = 4,
  ASSURANCE_CONTRACT_STUB = 5,
  // Raise fee, e.g. child-pays-for-parent.
  RAISE_FEE = 6,
}

/** A bitcoin transaction */
export interface Transaction {
  version: number;
  hash: Uint8Array;

  /**
   * If pool is not present, that means either:
   *  - This Transaction is either not in a wallet at all (the proto is re-used elsewhere)
   *  - Or it is stored but for other purposes, for example, because it is the overriding transaction of a double spend.
   *  - Or the Pool enum got a new value which your software is too old to parse.
   */
  pool?: TransactionPool;

  /** The nLockTime field is useful for contracts. */
  lock_time?: number;
  /**
   * millis since epoch the transaction was last updated
   * Note: 64-bit integers can exceed JavaScript's safe integer range.
   */
  updated_at?: number;

  transaction_input: TransactionInput[];
  transaction_output: TransactionOutput[];

  /**
   * A list of blocks in which the transaction has been observed (on any chain). Also, a number used to disambiguate
   * ordering within a block.
   */
  block_hash: Uint8Array[];
  block_relativity_offsets: number[];

  /** Data describing where the transaction is in the chain. */
  confidence?: TransactionConfidence;

  /** For what purpose the transaction was created. */
  purpose?: TransactionPurpose; // Default: UNKNOWN

  /** Exchange rate that was valid when the transaction was sent. */
  exchange_rate?: ExchangeRate;

  /**
   * Memo of the transaction. It can be used to record the memo of the payment request that initiated the
   * transaction.
   */
  memo?: string;
}

/**
 * The parameters used in the scrypt key derivation function.
 * The default values are taken from http://www.tarsnap.com/scrypt/scrypt-slides.pdf.
 * They can be increased - n is the number of iterations performed and
 * r and p can be used to tweak the algorithm - see:
 * http://stackoverflow.com/questions/11126315/what-are-optimal-scrypt-work-factors
 */
export interface ScryptParameters {
  /** Salt to use in generation of the wallet password (8 bytes) */
  salt: Uint8Array;
  /**
   * CPU/ memory cost parameter
   * Note: 64-bit integers can exceed JavaScript's safe integer range.
   */
  n?: number; // Default: 16384
  /** Block size parameter */
  r?: number; // Default: 8
  /** Parallelisation parameter */
  p?: number; // Default: 1
}

/** An extension to the wallet */
export interface Extension {
  /** like org.whatever.foo.bar */
  id: string;
  data: Uint8Array;
  /**
   * If we do not understand a mandatory extension, abort to prevent data loss.
   * For example, this could be applied to a new type of holding, such as a contract, where
   * dropping of an extension in a read/write cycle could cause loss of value.
   */
  mandatory: boolean;
}

/**
 * A simple key->value mapping that has no interpreted content at all. A bit like the extensions mechanism except
 * an extension is keyed by the ID of a piece of code that's loaded with the given data, and has the concept of
 * being mandatory if that code isn't found. Whereas this is just a blind key/value store.
 */
export interface Tag {
  tag: string;
  data: Uint8Array;
}

/**
 * Data required to reconstruct TransactionSigner.
 */
export interface TransactionSigner {
  /** fully qualified class name of TransactionSigner implementation */
  class_name: string;
  /** arbitrary data required for signer to function */
  data?: Uint8Array;
}

/**
 * The encryption type of the wallet.
 *
 * The encryption type is UNENCRYPTED for wallets where the wallet does not support encryption - wallets prior to
 * encryption support are grandfathered in as this wallet type.
 * When a wallet is ENCRYPTED_SCRYPT_AES the keys are either encrypted with the wallet password or are unencrypted.
 */
export enum WalletEncryptionType {
  UNENCRYPTED = 1, // All keys in the wallet are unencrypted
  ENCRYPTED_SCRYPT_AES = 2, // All keys are encrypted with a passphrase based KDF of scrypt and AES encryption
}

/** A bitcoin wallet */
export interface Wallet {
  /**
   * the network used by this wallet
   * org.bitcoin.production = main, production network (Satoshi genesis block)
   * org.bitcoin.test = test network (Andresen genesis block)
   */
  network_identifier: string;

  /** The SHA256 hash of the head of the best chain seen by this wallet. */
  last_seen_block_hash?: Uint8Array;
  /** The height in the chain of the last seen block. */
  last_seen_block_height?: number;
  /** Note: 64-bit integers can exceed JavaScript's safe integer range. */
  last_seen_block_time_secs?: number;

  key: Key[];
  transaction: Transaction[];
  watched_script: Script[];

  encryption_type?: WalletEncryptionType; // Default: UNENCRYPTED
  encryption_parameters?: ScryptParameters;

  /**
   * The version number of the wallet - used to detect wallets that were produced in the future
   * (i.e. the wallet may contain some future format this protobuf or parser code does not know about).
   * A version that's higher than the default is considered from the future.
   */
  version?: number; // Default: 1

  extension: Extension[];

  /** A UTF8 encoded text description of the wallet that is intended for end user provided text. */
  description?: string;

  /**
   * UNIX time in seconds since the epoch. If set, then any keys created before this date are assumed to be no longer
   * wanted. Money sent to them will be re-spent automatically to the first key that was created after this time. It
   * can be used to recover a compromised wallet, or just as part of preventative defence-in-depth measures.
   * Note: 64-bit integers can exceed JavaScript's safe integer range.
   */
  key_rotation_time?: number;

  tags: Tag[];

  /** transaction signers added to the wallet */
  transaction_signers: TransactionSigner[];
}

/** An exchange rate between Bitcoin and some fiat currency. */
export interface ExchangeRate {
  /**
   * This much of satoshis (1E-8 fractions)…
   * Note: 64-bit integers can exceed JavaScript's safe integer range.
   */
  coin_value: number;
  /**
   * …is worth this much of fiat (1E-4 fractions).
   * Note: 64-bit integers can exceed JavaScript's safe integer range.
   */
  fiat_value: number;
  /** ISO 4217 currency code (if available) of the fiat currency. */
  fiat_currency_code: string;
}