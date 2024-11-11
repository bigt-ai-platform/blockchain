/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
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
package net.bigtangle.wallet;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.InvalidCipherTextException;
import org.spongycastle.crypto.params.KeyParameter;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.math.LongMath;
import com.google.protobuf.ByteString;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Block.Type;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventCancelInfo;
import net.bigtangle.core.ContractEventInfo;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderCancelInfo;
import net.bigtangle.core.OrderOpenInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Side;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UserSettingDataInfo;
import net.bigtangle.core.Utils;
import net.bigtangle.core.VarInt;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.InsufficientMoneyException;
import net.bigtangle.core.exception.NoBlockException;
import net.bigtangle.core.exception.NoDataException;
import net.bigtangle.core.exception.NoTokenException;
import net.bigtangle.core.exception.ScriptException;
import net.bigtangle.core.exception.VerificationException.InvalidTransactionDataException;
import net.bigtangle.core.exception.VerificationException.OrderImpossibleException;
import net.bigtangle.core.exception.VerificationException.OrderWithRemainderException;
import net.bigtangle.core.ordermatch.MatchLastdayResult;
import net.bigtangle.core.response.GetDomainTokenResponse;
import net.bigtangle.core.response.GetOutputsResponse;
import net.bigtangle.core.response.GetTokensResponse;
import net.bigtangle.core.response.MultiSignByRequest;
import net.bigtangle.core.response.MultiSignResponse;
import net.bigtangle.core.response.OrderTickerResponse;
import net.bigtangle.core.response.OutputsDetailsResponse;
import net.bigtangle.core.response.PermissionedAddressesResponse;
import net.bigtangle.core.response.TokenIndexResponse;
import net.bigtangle.crypto.ChildNumber;
import net.bigtangle.crypto.DeterministicKey;
import net.bigtangle.crypto.KeyCrypter;
import net.bigtangle.crypto.KeyCrypterException;
import net.bigtangle.crypto.KeyCrypterScrypt;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.encrypt.ECIESCoder;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.pool.server.ServerPool;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.signers.LocalTransactionSigner;
import net.bigtangle.signers.MissingSigResolutionSigner;
import net.bigtangle.signers.TransactionSigner;
import net.bigtangle.utils.BaseTaggableObject;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.MonetaryFormat;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.utils.Threading;
import net.bigtangle.wallet.Protos.Wallet.EncryptionType;
import net.jcip.annotations.GuardedBy;

/**
 * <p>
 * A Wallet stores keys and provide common service for blocks and transactions
 * that send and receive value from those keys. Using these, it is able to
 * create new transactions that spend the recorded transactions, and this is the
 * fundamental operation of the protocol.
 * </p>
 * 
 * <p>
 * Wallets can be serialized using protocol buffers.
 * </p>
 */

public class Wallet extends BaseTaggableObject implements KeyBag {

	private static final Logger log = LoggerFactory.getLogger(Wallet.class);

	// Ordering: lock > keyChainGroupLock. KeyChainGroup is protected separately
	// to allow fast querying of current receive address
	// even if the wallet itself is busy e.g. saving or processing a big reorg.
	// Useful for reducing UI latency.
	protected final ReentrantLock lock = Threading.lock("wallet");
	protected final ReentrantLock keyChainGroupLock = Threading.lock("wallet-keychaingroup");

	// The various pools below give quick access to wallet-relevant transactions

	// server url for connected
	protected ServerPool serverPool;
	// indicator, if the fee add to transaction
	protected Boolean fee = true;
	// To avoid conflict, it uses first only output of not spent
	private static final int SPENTPENDINGTIMEOUT = 120000;// 2 minutues

	// The key chain group is not thread safe, and generally the whole hierarchy
	// of objects should not be mutated
	// outside the wallet lock. So don't expose this object directly via any
	// accessors!
	@GuardedBy("keyChainGroupLock")
	private KeyChainGroup keyChainGroup;

	// A list of scripts watched by this wallet.
	@GuardedBy("keyChainGroupLock")
	private Set<Script> watchedScripts;

	protected final NetworkParameters params;

	protected volatile WalletFiles vFileManager;
	// Object that is used to send transactions asynchronously when the wallet
	// requires it.

	// UNIX time in seconds. Money controlled by keys created before this time
	// will be automatically respent to a key
	// that was created after it. Useful when you believe some keys have been
	// compromised.
	private volatile long vKeyRotationTimestamp;

	protected CoinSelector coinSelector = new DefaultCoinSelector();

	// The wallet version. This is an int that can be used to track breaking
	// changes in the wallet format.
	// You can also use it to detect wallets that come from the future (ie they
	// contain features you
	// do not know how to deal with).
	private int version;

	// Objects that perform transaction signing. Applied subsequently one after
	// another
	@GuardedBy("lock")
	private List<TransactionSigner> signers;

	public static Wallet fromSeed(NetworkParameters params, DeterministicSeed seed) {
		return new Wallet(params, new KeyChainGroup(params, seed), null);
	}

	/**
	 * Creates a wallet that tracks payments to and from the HD key hierarchy rooted
	 * by the given watching key. A watching key corresponds to account zero in the
	 * recommended BIP32 key hierarchy.
	 */
	public static Wallet fromKeys(NetworkParameters params, List<ECKey> keys) {
		for (ECKey key : keys)
			checkArgument(!(key instanceof DeterministicKey));

		KeyChainGroup group = new KeyChainGroup(params);
		group.importKeys(keys);
		return new Wallet(params, group);
	}

	public Wallet(NetworkParameters params) {
		this(params, new KeyChainGroup(params), null);
	}

	/*
	 * Creates a wallet containing a given set of keys. All further keys will be
	 * derived from the oldest key.
	 */
	public static Wallet fromKeys(NetworkParameters params, ECKey key) {

		return fromKeys(params, key, null);
	}

	public static Wallet fromKeys(NetworkParameters params, ECKey key, String url) {

		checkArgument(!(key instanceof DeterministicKey));
		List<ECKey> keys = new ArrayList<ECKey>();
		keys.add(key);
		KeyChainGroup group = new KeyChainGroup(params);
		group.importKeys(keys);
		return new Wallet(params, group, url);
	}

	public Wallet(NetworkParameters params, KeyChainGroup keyChainGroup) {
		this(params, keyChainGroup, null);
	}

	private Wallet(NetworkParameters params, KeyChainGroup keyChainGroup, String url) {

		this.params = params;
		this.keyChainGroup = checkNotNull(keyChainGroup);
		if (params.getId().equals(NetworkParameters.ID_UNITTESTNET))
			this.keyChainGroup.setLookaheadSize(5); // Cut down excess
													// computation for unit
													// tests.
		// If this keyChainGroup was created fresh just now (new wallet), make
		// HD so a backup can be made immediately
		// without having to call current/freshReceiveKey. If there are already
		// keys in the chain of any kind then
		// we're probably being deserialized so leave things alone: the API user
		// can upgrade later.
		if (this.keyChainGroup.numKeys() == 0)
			this.keyChainGroup.createAndActivateNewHDChain();
		watchedScripts = Sets.newHashSet();

		signers = new ArrayList<TransactionSigner>();
		addTransactionSigner(new LocalTransactionSigner());
		if (url == null) {
			this.serverPool = new ServerPool(params);
		} else {
			setServerURL(url);
		}
	}

	public NetworkParameters getNetworkParameters() {
		return params;
	}

	/**
	 * Gets the active keychain via {@link KeyChainGroup#getActiveKeyChain()}
	 */
	public DeterministicKeyChain getActiveKeyChain() {
		return keyChainGroup.getActiveKeyChain();
	}

	/**
	 * <p>
	 * Adds given transaction signer to the list of signers. It will be added to the
	 * end of the signers list, so if this wallet already has some signers added,
	 * given signer will be executed after all of them.
	 * </p>
	 * <p>
	 * Transaction signer should be fully initialized before adding to the wallet,
	 * otherwise {@link IllegalStateException} will be thrown
	 * </p>
	 */
	public final void addTransactionSigner(TransactionSigner signer) {
		lock.lock();
		try {
			if (signer.isReady())
				signers.add(signer);
			else
				throw new IllegalStateException(
						"Signer instance is not ready to be added into Wallet: " + signer.getClass());
		} finally {
			lock.unlock();
		}
	}

	public List<TransactionSigner> getTransactionSigners() {
		lock.lock();
		try {
			return ImmutableList.copyOf(signers);
		} finally {
			lock.unlock();
		}
	}

	/******************************************************************************************************************/

	// region Key Management

	/**
	 * Upgrades the wallet to be deterministic (BIP32). You should call this,
	 * possibly providing the users encryption key, after loading a wallet produced
	 * by previous versions of bitcoinj. If the wallet is encrypted the key
	 * <b>must</b> be provided, due to the way the seed is derived deterministically
	 * from private key bytes: failing to do this will result in an exception being
	 * thrown. For non-encrypted wallets, the upgrade will be done for you
	 * automatically the first time a new key is requested (this happens when
	 * spending due to the change address).
	 */
	public void upgradeToDeterministic(@Nullable KeyParameter aesKey) throws DeterministicUpgradeRequiresPassword {
		keyChainGroupLock.lock();
		try {
			keyChainGroup.upgradeToDeterministic(vKeyRotationTimestamp, aesKey);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Returns true if the wallet contains random keys and no HD chains, in which
	 * case you should call
	 * {@link #upgradeToDeterministic(org.spongycastle.crypto.params.KeyParameter)}
	 * before attempting to do anything that would require a new address or key.
	 */
	public boolean isDeterministicUpgradeRequired() {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.isDeterministicUpgradeRequired();
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	private void maybeUpgradeToHD() throws DeterministicUpgradeRequiresPassword {
		maybeUpgradeToHD(null);
	}

	@GuardedBy("keyChainGroupLock")
	private void maybeUpgradeToHD(@Nullable KeyParameter aesKey) throws DeterministicUpgradeRequiresPassword {
		checkState(keyChainGroupLock.isHeldByCurrentThread());
		if (keyChainGroup.isDeterministicUpgradeRequired()) {
			log.info("Upgrade to HD wallets is required, attempting to do so.");
			try {
				upgradeToDeterministic(aesKey);
			} catch (DeterministicUpgradeRequiresPassword e) {
				log.error("Failed to auto upgrade due to encryption. You should call wallet.upgradeToDeterministic "
						+ "with the users AES key to avoid this error.");
				throw e;
			}
		}
	}

	/**
	 * Removes the given key from the basicKeyChain. Be very careful with this -
	 * losing a private key <b>destroys the money associated with it</b>.
	 * 
	 * @return Whether the key was removed or not.
	 */
	public boolean removeKey(ECKey key) {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.removeImportedKey(key);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Returns the number of keys in the key chain group, including lookahead keys.
	 */
	public int getKeyChainGroupSize() {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.numKeys();
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	@VisibleForTesting
	public int getKeyChainGroupCombinedKeyLookaheadEpochs() {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.getCombinedKeyLookaheadEpochs();
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Returns a list of the non-deterministic keys that have been imported into the
	 * wallet, or the empty list if none.
	 */
	public List<ECKey> getImportedKeys() {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.getImportedKeys();
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * <p>
	 * Imports the given ECKey to the wallet.
	 * </p>
	 *
	 * <p>
	 * If the wallet is configured to auto save to a file, triggers a save
	 * immediately. Runs the onKeysAdded event handler. If the key already exists in
	 * the wallet, does nothing and returns false.
	 * </p>
	 */
	public boolean importKey(ECKey key) {
		return importKeys(Lists.newArrayList(key)) == 1;
	}

	/**
	 * Imports the given keys to the wallet. If
	 * {@link Wallet#autosaveToFile(java.io.File, long, java.util.concurrent.TimeUnit, net.bigtangle.wallet.WalletFiles.Listener)}
	 * has been called, triggers an auto save bypassing the normal coalescing delay
	 * and event handlers. Returns the number of keys added, after duplicates are
	 * ignored. The onKeyAdded event will be called for each key in the list that
	 * was not already present.
	 */
	public int importKeys(final List<ECKey> keys) {
		// API usage check.
		checkNoDeterministicKeys(keys);
		int result;
		keyChainGroupLock.lock();
		try {
			result = keyChainGroup.importKeys(keys);
		} finally {
			keyChainGroupLock.unlock();
		}
		saveNow();
		return result;
	}

	private void checkNoDeterministicKeys(List<ECKey> keys) {
		// Watch out for someone doing
		// wallet.importKey(wallet.freshReceiveKey()); or equivalent: we never
		// tested this.
		for (ECKey key : keys)
			if (key instanceof DeterministicKey)
				throw new IllegalArgumentException("Cannot import HD keys back into the wallet");
	}

	/**
	 * Takes a list of keys and a password, then encrypts and imports them in one
	 * step using the current keycrypter.
	 */
	public int importKeysAndEncrypt(final List<ECKey> keys, CharSequence password) {
		keyChainGroupLock.lock();
		int result;
		try {
			checkNotNull(getKeyCrypter(), "Wallet is not encrypted");
			result = importKeysAndEncrypt(keys, getKeyCrypter().deriveKey(password));
		} finally {
			keyChainGroupLock.unlock();
		}
		saveNow();
		return result;
	}

	/**
	 * Takes a list of keys and an AES key, then encrypts and imports them in one
	 * step using the current keycrypter.
	 */
	public int importKeysAndEncrypt(final List<ECKey> keys, KeyParameter aesKey) {
		keyChainGroupLock.lock();
		try {
			checkNoDeterministicKeys(keys);
			return keyChainGroup.importKeysAndEncrypt(keys, aesKey);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * See {@link net.bigtangle.wallet.DeterministicKeyChain#setLookaheadSize(int)}
	 * for more info on this.
	 */
	public void setKeyChainGroupLookaheadSize(int lookaheadSize) {
		keyChainGroupLock.lock();
		try {
			keyChainGroup.setLookaheadSize(lookaheadSize);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * See {@link net.bigtangle.wallet.DeterministicKeyChain#setLookaheadSize(int)}
	 * for more info on this.
	 */
	public int getKeyChainGroupLookaheadSize() {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.getLookaheadSize();
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * See
	 * {@link net.bigtangle.wallet.DeterministicKeyChain#setLookaheadThreshold(int)}
	 * for more info on this.
	 */
	public void setKeyChainGroupLookaheadThreshold(int num) {
		keyChainGroupLock.lock();
		try {
			maybeUpgradeToHD();
			keyChainGroup.setLookaheadThreshold(num);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * See
	 * {@link net.bigtangle.wallet.DeterministicKeyChain#setLookaheadThreshold(int)}
	 * for more info on this.
	 */
	public int getKeyChainGroupLookaheadThreshold() {
		keyChainGroupLock.lock();
		try {
			maybeUpgradeToHD();
			return keyChainGroup.getLookaheadThreshold();
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/*
	 * Locates a keypair from the basicKeyChain given the hash of the public key.
	 * This is needed when finding out which key we need to use to redeem a
	 * transaction output.
	 *
	 * @return ECKey object or null if no such key was found.
	 */
	@Override
	@Nullable
	public ECKey findKeyFromPubHash(byte[] pubkeyHash) {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.findKeyFromPubHash(pubkeyHash);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Returns true if the given key is in the wallet, false otherwise. Currently an
	 * O(N) operation.
	 */
	public boolean hasKey(ECKey key) {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.hasKey(key);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Locates a keypair from the basicKeyChain given the raw public key bytes.
	 * 
	 * @return ECKey or null if no such key was found.
	 */
	@Override
	@Nullable
	public ECKey findKeyFromPubKey(byte[] pubkey) {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.findKeyFromPubKey(pubkey);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Locates a redeem data (redeem script and keys) from the keyChainGroup given
	 * the hash of the script. Returns RedeemData object or null if no such data was
	 * found.
	 */
	@Nullable
	@Override
	public RedeemData findRedeemDataFromScriptHash(byte[] payToScriptHash) {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.findRedeemDataFromScriptHash(payToScriptHash);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Returns the immutable seed for the current active HD chain.
	 * 
	 * @throws net.bigtangle.core.ECKey.MissingPrivateKeyException if the seed is
	 *                                                             unavailable
	 *                                                             (watching wallet)
	 */
	public DeterministicSeed getKeyChainSeed() {
		keyChainGroupLock.lock();
		try {
			DeterministicSeed seed = keyChainGroup.getActiveKeyChain().getSeed();
			if (seed == null)
				throw new ECKey.MissingPrivateKeyException();
			return seed;
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Returns a key for the given HD path, assuming it's already been derived. You
	 * normally shouldn't use this: use currentReceiveKey/freshReceiveKey instead.
	 */
	public DeterministicKey getKeyByPath(List<ChildNumber> path) {
		keyChainGroupLock.lock();
		try {
			maybeUpgradeToHD();
			return keyChainGroup.getActiveKeyChain().getKeyByPath(path, false);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Convenience wrapper around
	 * {@link Wallet#encrypt(net.bigtangle.crypto.KeyCrypter, org.spongycastle.crypto.params.KeyParameter)}
	 * which uses the default Scrypt key derivation algorithm and parameters to
	 * derive a key from the given password.
	 */
	public void encrypt(CharSequence password) {
		keyChainGroupLock.lock();
		try {
			final KeyCrypterScrypt scrypt = new KeyCrypterScrypt();
			keyChainGroup.encrypt(scrypt, scrypt.deriveKey(password));
		} finally {
			keyChainGroupLock.unlock();
		}
		saveNow();
	}

	/**
	 * Encrypt the wallet using the KeyCrypter and the AES key. A good default
	 * KeyCrypter to use is {@link net.bigtangle.crypto.KeyCrypterScrypt}.
	 *
	 * @param keyCrypter The KeyCrypter that specifies how to encrypt/ decrypt a key
	 * @param aesKey     AES key to use (normally created using KeyCrypter#deriveKey
	 *                   and cached as it is time consuming to create from a
	 *                   password)
	 * @throws KeyCrypterException Thrown if the wallet encryption fails. If so, the
	 *                             wallet state is unchanged.
	 */
	public void encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) {
		keyChainGroupLock.lock();
		try {
			keyChainGroup.encrypt(keyCrypter, aesKey);
		} finally {
			keyChainGroupLock.unlock();
		}
		saveNow();
	}

	/**
	 * Decrypt the wallet with the wallets keyCrypter and password.
	 * 
	 * @throws KeyCrypterException Thrown if the wallet decryption fails. If so, the
	 *                             wallet state is unchanged.
	 */
	public void decrypt(CharSequence password) {
		keyChainGroupLock.lock();
		try {
			final KeyCrypter crypter = keyChainGroup.getKeyCrypter();
			checkState(crypter != null, "Not encrypted");
			keyChainGroup.decrypt(crypter.deriveKey(password));
		} finally {
			keyChainGroupLock.unlock();
		}
		saveNow();
	}

	/**
	 * Decrypt the wallet with the wallets keyCrypter and AES key.
	 *
	 * @param aesKey AES key to use (normally created using KeyCrypter#deriveKey and
	 *               cached as it is time consuming to create from a password)
	 * @throws KeyCrypterException Thrown if the wallet decryption fails. If so, the
	 *                             wallet state is unchanged.
	 */
	public void decrypt(KeyParameter aesKey) {
		keyChainGroupLock.lock();
		try {
			keyChainGroup.decrypt(aesKey);
		} finally {
			keyChainGroupLock.unlock();
		}
		saveNow();
	}

	/**
	 * Check whether the password can decrypt the first key in the wallet. This can
	 * be used to check the validity of an entered password.
	 *
	 * @return boolean true if password supplied can decrypt the first private key
	 *         in the wallet, false otherwise.
	 * @throws IllegalStateException if the wallet is not encrypted.
	 */
	public boolean checkPassword(CharSequence password) {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.checkPassword(password);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Check whether the AES key can decrypt the first encrypted key in the wallet.
	 *
	 * @return boolean true if AES key supplied can decrypt the first encrypted
	 *         private key in the wallet, false otherwise.
	 */
	public boolean checkAESKey(KeyParameter aesKey) {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.checkAESKey(aesKey);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Get the wallet's KeyCrypter, or null if the wallet is not encrypted. (Used in
	 * encrypting/ decrypting an ECKey).
	 */
	@Nullable
	public KeyCrypter getKeyCrypter() {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.getKeyCrypter();
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Get the type of encryption used for this wallet.
	 *
	 * (This is a convenience method - the encryption type is actually stored in the
	 * keyCrypter).
	 */
	public EncryptionType getEncryptionType() {
		keyChainGroupLock.lock();
		try {
			KeyCrypter crypter = keyChainGroup.getKeyCrypter();
			if (crypter != null)
				return crypter.getUnderstoodEncryptionType();
			else
				return EncryptionType.UNENCRYPTED;
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * Returns true if the wallet is encrypted using any scheme, false if not.
	 */
	public boolean isEncrypted() {
		return getEncryptionType() != EncryptionType.UNENCRYPTED;
	}

	/** Changes wallet encryption password, this is atomic operation. */
	public void changeEncryptionPassword(CharSequence currentPassword, CharSequence newPassword) {
		keyChainGroupLock.lock();
		try {
			decrypt(currentPassword);
			encrypt(newPassword);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/** Changes wallet AES encryption key, this is atomic operation. */
	public void changeEncryptionKey(KeyCrypter keyCrypter, KeyParameter currentAesKey, KeyParameter newAesKey) {
		keyChainGroupLock.lock();
		try {
			decrypt(currentAesKey);
			encrypt(keyCrypter, newAesKey);
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	// endregion

	/******************************************************************************************************************/

	// region Serialization support

	/** Internal use only. */
	protected List<Protos.Key> serializeKeyChainGroupToProtobuf() {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.serializeToProtobuf();
		} finally {
			keyChainGroupLock.unlock();
		}
	}

	/**
	 * 
	 * Saves the wallet first to the given temp file, then renames to the dest file.
	 */
	public void saveToFile(File temp, File destFile) throws IOException {
		FileOutputStream stream = null;
		lock.lock();
		try {
			stream = new FileOutputStream(temp);
			saveToFileStream(stream);
			// Attempt to force the bits to hit the disk. In reality the OS or
			// hard disk itself may still decide
			// to not write through to physical media for at least a few
			// seconds, but this is the best we can do.
			stream.flush();
			stream.getFD().sync();
			stream.close();
			stream = null;
			if (Utils.isWindows()) {
				// Work around an issue on Windows whereby you can't rename over
				// existing files.
				File canonical = destFile.getCanonicalFile();
				if (canonical.exists() && !canonical.delete())
					throw new IOException("Failed to delete canonical wallet file for replacement with autosave");
				if (temp.renameTo(canonical))
					return; // else fall through.
				throw new IOException("Failed to rename " + temp + " to " + canonical);
			} else if (!temp.renameTo(destFile)) {
				throw new IOException("Failed to rename " + temp + " to " + destFile);
			}
		} catch (RuntimeException e) {
			log.error("Failed whilst saving wallet", e);
			throw e;
		} finally {
			lock.unlock();
			if (stream != null) {
				stream.close();
			}
			if (temp.exists()) {
				log.warn("Temp file still exists after failed save.");
			}
		}
	}

	/**
	 * Saves the wallet first to the given temp file, then renames to the dest file.
	 */
	public void saveTo(OutputStream stream) throws IOException {

		lock.lock();
		try {
			saveToFileStream(stream);
			stream.flush();

			stream.close();
			stream = null;
		} finally {
			lock.unlock();
			if (stream != null) {
				stream.close();
			}
		}

	}

	/**
	 * Uses protobuf serialization to save the wallet to the given file. To learn
	 * more about this file format, see {@link WalletProtobufSerializer}. Writes out
	 * first to a temporary file in the same directory and then renames once
	 * written.
	 */
	public void saveToFile(File f) throws IOException {
		File directory = f.getAbsoluteFile().getParentFile();
		File temp = File.createTempFile("wallet", null, directory);
		saveToFile(temp, f);
	}

	public void saveNow() {
		WalletFiles files = vFileManager;
		if (files != null) {
			try {
				files.saveNow(); // This calls back into saveToFile().
			} catch (IOException e) {
				// Can't really do much at this point, just let the API user
				// know.
				log.error("Failed to save wallet to disk!", e);
				Thread.UncaughtExceptionHandler handler = Threading.uncaughtExceptionHandler;
				if (handler != null)
					handler.uncaughtException(Thread.currentThread(), e);
			}
		}
	}

	/**
	 * Uses protobuf serialization to save the wallet to the given file stream. To
	 * learn more about this file format, see {@link WalletProtobufSerializer}.
	 */
	public void saveToFileStream(OutputStream f) throws IOException {
		lock.lock();
		try {
			new WalletProtobufSerializer().writeWallet(this, f);
		} finally {
			lock.unlock();
		}
	}

	/** Returns the parameters this wallet was created with. */
	public NetworkParameters getParams() {
		return params;
	}

	/**
	 * Get the version of the Wallet. This is an int you can use to indicate which
	 * versions of wallets your code understands, and which come from the future
	 * (and hence cannot be safely loaded).
	 */
	public int getVersion() {
		return version;
	}

	/**
	 * Set the version number of the wallet. See {@link Wallet#getVersion()}.
	 */
	public void setVersion(int version) {
		this.version = version;
	}

	/**
	 * Enumerates possible resolutions for missing signatures.
	 */
	public enum MissingSigsMode {
		/** Input script will have OP_0 instead of missing signatures */
		USE_OP_ZERO,
		/**
		 * Missing signatures will be replaced by dummy sigs. This is useful when you'd
		 * like to know the fee for a transaction without knowing the user's password,
		 * as fee depends on size.
		 */
		USE_DUMMY_SIG,
		/**
		 * If signature is missing,
		 * {@link org.bitcoinj.signers.TransactionSigner.MissingSignatureException} will
		 * be thrown for P2SH and {@link ECKey.MissingPrivateKeyException} for other tx
		 * types.
		 */
		THROW
	}

	/**
	 * <p>
	 * Given a send request containing transaction, attempts to sign it's inputs.
	 * This method expects transaction to have all necessary inputs connected or
	 * they will be ignored.
	 * </p>
	 * <p>
	 * Actual signing is done by pluggable {@link #signers} and it's not guaranteed
	 * that transaction will be complete in the end.
	 * </p>
	 */
	public void signTransaction(SendRequest req) {
		signTransaction(req.tx, req.aesKey, req.missingSigsMode);
	}

	public void signTransaction(Transaction tx, KeyParameter aesKey, MissingSigsMode missingSigsMode) {
		lock.lock();
		try {

			List<TransactionInput> inputs = tx.getInputs();
			List<TransactionOutput> outputs = tx.getOutputs();
			checkState(inputs.size() > 0);
			checkState(outputs.size() > 0);

			KeyBag maybeDecryptingKeyBag = new DecryptingKeyBag(this, aesKey);

			int numInputs = tx.getInputs().size();
			for (int i = 0; i < numInputs; i++) {
				TransactionInput txIn = tx.getInput(i);
				if (txIn.getConnectedOutput() == null) {
					// Missing connected output, assuming already signed.
					continue;
				}

				Script scriptPubKey = txIn.getConnectedOutput().getScriptPubKey();
				RedeemData redeemData = txIn.getConnectedRedeemData(maybeDecryptingKeyBag);
				// checkNotNull(redeemData, "Transaction exists in wallet that
				// we cannot redeem: %s",
				// txIn.getOutpoint().getHash());
				if (redeemData != null)
					txIn.setScriptSig(
							scriptPubKey.createEmptyInputScript(redeemData.keys.get(0), redeemData.redeemScript));
			}

			TransactionSigner.ProposedTransaction proposal = new TransactionSigner.ProposedTransaction(tx);
			for (TransactionSigner signer : signers) {
				if (!signer.signInputs(proposal, maybeDecryptingKeyBag))
					log.info("{} returned false for the tx", signer.getClass().getName());
			}

			// resolve missing sigs if any
			new MissingSigResolutionSigner(missingSigsMode).signInputs(proposal, maybeDecryptingKeyBag);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * <p>
	 * Given a transaction, attempts to sign it's inputs. This method expects
	 * transaction to have all necessary inputs connected or they will be ignored.
	 * </p>
	 * <p>
	 * Actual signing is done by pluggable {@link #signers} and it's not guaranteed
	 * that transaction will be complete in the end.
	 * </p>
	 */
	public void signTransaction(Transaction tx, KeyParameter aesKey) {
		signTransaction(tx, aesKey, MissingSigsMode.THROW);
	}

	/**
	 * Returns true if this wallet has at least one of the private keys needed to
	 * sign for this scriptPubKey. Returns false if the form of the script is not
	 * known or if the script is OP_RETURN.
	 */
	public boolean canSignFor(Script script) {
		if (script.isSentToRawPubKey()) {
			byte[] pubkey = script.getPubKey();
			ECKey key = findKeyFromPubKey(pubkey);
			return key != null && (key.isEncrypted() || key.hasPrivKey());
		}
		if (script.isPayToScriptHash()) {
			RedeemData data = findRedeemDataFromScriptHash(script.getPubKeyHash());
			return data != null && canSignFor(data.redeemScript);
		} else if (script.isSentToAddress()) {
			ECKey key = findKeyFromPubHash(script.getPubKeyHash());
			return key != null && (key.isEncrypted() || key.hasPrivKey());
		} else if (script.isSentToMultiSig()) {
			for (ECKey pubkey : script.getPubKeys()) {
				ECKey key = findKeyFromPubKey(pubkey.getPubKey());
				if (key != null && (key.isEncrypted() || key.hasPrivKey()))
					return true;
			}
		} else if (script.isSentToCLTVPaymentChannel()) {
			// Any script for which we are the recipient or sender counts.
			byte[] sender = script.getCLTVPaymentChannelSenderPubKey();
			ECKey senderKey = findKeyFromPubKey(sender);
			if (senderKey != null && (senderKey.isEncrypted() || senderKey.hasPrivKey())) {
				return true;
			}

			ECKey recipientKey = findKeyFromPubKey(sender);
			if (recipientKey != null && (recipientKey.isEncrypted() || recipientKey.hasPrivKey())) {
				return true;
			}
			return false;
		}
		return false;
	}

	/**
	 * Returns the {@link CoinSelector} object which controls which outputs can be
	 * spent by this wallet.
	 */
	public CoinSelector getCoinSelector() {
		lock.lock();
		try {
			return coinSelector;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * A coin selector is responsible for choosing which outputs to spend when
	 * creating transactions. The default selector implements a policy of spending
	 * transactions that appeared in the best chain and pending transactions that
	 * were created by this wallet, but not others. You can override the coin
	 * selector for any given send operation by changing
	 * {@link SendRequest#coinSelector}.
	 */
	public void setCoinSelector(CoinSelector coinSelector) {
		lock.lock();
		try {
			this.coinSelector = checkNotNull(coinSelector);
		} finally {
			lock.unlock();
		}
	}

	/******************************************************************************************************************/

	public WalletFiles autosaveToFile(File f, long delayTime, TimeUnit timeUnit,
			@Nullable WalletFiles.Listener eventListener) {
		lock.lock();
		try {
			checkState(vFileManager == null, "Already auto saving this wallet.");
			WalletFiles manager = new WalletFiles(this, f, delayTime, timeUnit);
			if (eventListener != null)
				manager.setListener(eventListener);
			vFileManager = manager;
			return manager;
		} finally {
			lock.unlock();
		}
	}

	/******************************************************************************************************************/

	protected static class FeeCalculation {
		public CoinSelection bestCoinSelection;
		public TransactionOutput bestChangeOutput;
	}

	public FeeCalculation calculateFee(SendRequest req, Coin value, List<TransactionInput> originalInputs,
			boolean needAtLeastReferenceFee, List<FreeStandingTransactionOutput> candidates, Address changeAddress)
			throws InsufficientMoneyException {
		checkState(lock.isHeldByCurrentThread());
		// There are 3 possibilities for what adding change might do:
		// 1) No effect
		// 2) Causes increase in fee (change < 0.01 COINS)
		// 3) Causes the transaction to have a dust output or change < fee
		// increase (ie change will be thrown away)
		// If we get either of the last 2, we keep note of what the inputs
		// looked like at the time and try to
		// add inputs as we go up the list (keeping track of minimum inputs for
		// each category). At the end, we pick
		// the best input set as the one which generates the lowest total fee.
		Coin additionalValueForNextCategory = null;
		CoinSelection selection3 = null;
		CoinSelection selection2 = null;
		TransactionOutput selection2Change = null;
		CoinSelection selection1 = null;
		TransactionOutput selection1Change = null;
		// We keep track of the last size of the transaction we calculated.
		int lastCalculatedSize = 0;
		Coin valueNeeded, valueMissing = null;

		while (true) {
			resetTxInputs(req, originalInputs);

			valueNeeded = value;
			if (additionalValueForNextCategory != null)
				valueNeeded = valueNeeded.add(additionalValueForNextCategory);
			Coin additionalValueSelected = additionalValueForNextCategory;

			// Of the coins we could spend, pick some that we actually will
			// spend.
			CoinSelector selector = req.coinSelector == null ? coinSelector : req.coinSelector;
			// selector is allowed to modify candidates list.
			CoinSelection selection = selector.select(valueNeeded, new LinkedList<TransactionOutput>(candidates));
			// Can we afford this?
			if (selection.valueGathered.compareTo(valueNeeded) < 0) {
				valueMissing = valueNeeded.subtract(selection.valueGathered);
				break;
			}
			checkState(selection.gathered.size() > 0 || originalInputs.size() > 0);

			// We keep track of an upper bound on transaction size to
			// calculate
			// fees that need to be added.
			// Note that the difference between the upper bound and lower
			// bound
			// is usually small enough that it
			// will be very rare that we pay a fee we do not need to.
			//
			// We can't be sure a selection is valid until we check fee per
			// kb
			// at the end, so we just store
			// them here temporarily.
			boolean eitherCategory2Or3 = false;
			boolean isCategory3 = false;

			Coin change = selection.valueGathered.subtract(valueNeeded);
			if (additionalValueSelected != null)
				change = change.add(additionalValueSelected);

			int size = 0;
			TransactionOutput changeOutput = null;
			if (change.signum() > 0) {
				// The value of the inputs is greater than what we want to
				// send.
				// Just like in real life then,
				// we need to take back some coins ... this is called
				// "change".
				// Add another output that sends the change
				// back to us. The address comes either from the request or
				// currentChangeAddress() as a default.
				// Address changeAddress = req.changeAddress;
				if (changeAddress == null)
					throw new RuntimeException(" no changeAddress");
				changeOutput = new TransactionOutput(params, req.tx, change, changeAddress);
				// If the change output would result in this transaction
				// being
				// rejected as dust, just drop the change and make it a fee

				size += changeOutput.unsafeBitcoinSerialize().length + VarInt.sizeOf(req.tx.getOutputs().size())
						- VarInt.sizeOf(req.tx.getOutputs().size() - 1);
				// This solution is either category 1 or 2
				if (!eitherCategory2Or3) // must be category 1
					additionalValueForNextCategory = null;

			} else {
				if (eitherCategory2Or3) {
					// This solution definitely fits in category 3 (we threw
					// away change because it was smaller than MIN_TX_FEE)
					isCategory3 = true;
					additionalValueForNextCategory = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;
				}
			}

			// Now add unsigned inputs for the selected coins.
			for (TransactionOutput output : selection.gathered) {
				TransactionInput input = req.tx
						.addInput(((FreeStandingTransactionOutput) output).getUTXO().getBlockHash(), output);
				// If the scriptBytes don't default to none, our size
				// calculations will be thrown off.
				checkState(input.getScriptBytes().length == 0);
			}

			// Estimate transaction size and loop again if we need more fee
			// per
			// kb. The serialized tx doesn't
			// include things we haven't added yet like input
			// signatures/scripts
			// or the change output.
			size += req.tx.unsafeBitcoinSerialize().length;
			size += estimateBytesForSigning(selection);
			if (size > lastCalculatedSize && req.feePerKb.signum() > 0) {
				lastCalculatedSize = size;
				// We need more fees anyway, just try again with the same
				// additional value
				additionalValueForNextCategory = additionalValueSelected;
				continue;
			}

			if (isCategory3) {
				if (selection3 == null)
					selection3 = selection;
			} else if (eitherCategory2Or3) {
				// If we are in selection2, we will require at least CENT
				// additional. If we do that, there is no way
				// we can end up back here because CENT additional will
				// always
				// get us to 1
				checkState(selection2 == null);

				selection2 = selection;
				selection2Change = checkNotNull(changeOutput); // If we get
																// no
																// change in
																// category
																// 2, we
																// are
																// actually
																// in
																// category 3
			} else {
				// Once we get a category 1 (change kept), we should break
				// out
				// of the loop because we can't do better
				// checkState(selection1 == null);
				checkState(additionalValueForNextCategory == null);
				selection1 = selection;
				selection1Change = changeOutput;
			}

			if (additionalValueForNextCategory != null) {
				if (additionalValueSelected != null)
					checkState(additionalValueForNextCategory.compareTo(additionalValueSelected) > 0);
				continue;
			}
			break;
		}

		resetTxInputs(req, originalInputs);

		if (selection3 == null && selection2 == null && selection1 == null) {
			checkNotNull(valueMissing);
			// log.warn("Insufficient value in wallet for send: needed {} more",
			// valueMissing.toString());
			throw new InsufficientMoneyException(valueMissing.toString());
		}

		Coin lowestFee = null;
		FeeCalculation result = new FeeCalculation();
		if (selection1 != null) {
			if (selection1Change != null)
				lowestFee = selection1.valueGathered.subtract(selection1Change.getValue());
			else
				lowestFee = selection1.valueGathered;
			result.bestCoinSelection = selection1;
			result.bestChangeOutput = selection1Change;
		}

		if (selection2 != null) {
			Coin fee = selection2.valueGathered.subtract(checkNotNull(selection2Change).getValue());
			if (lowestFee == null || fee.compareTo(lowestFee) < 0) {
				lowestFee = fee;
				result.bestCoinSelection = selection2;
				result.bestChangeOutput = selection2Change;
			}
		}

		if (selection3 != null) {
			if (lowestFee == null || selection3.valueGathered.compareTo(lowestFee) < 0) {
				result.bestCoinSelection = selection3;
				result.bestChangeOutput = null;
			}
		}
		return result;
	}

	private void resetTxInputs(SendRequest req, List<TransactionInput> originalInputs) {
		req.tx.clearInputs();
		for (TransactionInput input : originalInputs)
			req.tx.addInput(input);
	}

	private int estimateBytesForSigning(CoinSelection selection) {
		int size = 0;
		for (TransactionOutput output : selection.gathered) {
			try {
				Script script = output.getScriptPubKey();
				ECKey key = null;
				Script redeemScript = null;
				if (script.isSentToAddress()) {
					key = findKeyFromPubHash(script.getPubKeyHash());
					// Expected checkNotNull(key, "Coin selection includes
					// unspendable outputs");
				} else if (script.isPayToScriptHash()) {
					redeemScript = findRedeemDataFromScriptHash(script.getPubKeyHash()).redeemScript;
					checkNotNull(redeemScript, "Coin selection includes unspendable outputs");
				}
				size += script.getNumberOfBytesRequiredToSpend(key, redeemScript);
			} catch (ScriptException e) {
				// If this happens it means an output script in a wallet tx
				// could not be understood. That should never
				// happen, if it does it means the wallet has got into an
				// inconsistent state.
				throw new IllegalStateException(e);
			}
		}
		return size;
	}

	// endregion

	/******************************************************************************************************************/

	// region Wallet maintenance transactions

	// Wallet maintenance transactions. These transactions may not be directly
	// connected to a payment the user is
	// making. They may be instead key rotation transactions for when old keys
	// are suspected to be compromised,
	// de/re-fragmentation transactions for when our output sizes are
	// inappropriate or suboptimal, privacy transactions
	// and so on. Because these transactions may require user intervention in
	// some way (e.g. entering their password)
	// the wallet application is expected to poll the Wallet class to get
	// SendRequests. Ideally security systems like
	// hardware wallets or risk analysis providers are programmed to
	// auto-approve transactions that send from our own
	// keys back to our own keys.

	/**
	 * When a key rotation time is set, and money controlled by keys created before
	 * the given timestamp T will be automatically respent to any key that was
	 * created after T. This can be used to recover from a situation where a set of
	 * keys is believed to be compromised. Once the time is set transactions will be
	 * created and broadcast immediately. New coins that come in after calling this
	 * method will be automatically respent immediately. The rotation time is
	 * persisted to the wallet. You can stop key rotation by calling this method
	 * again with zero as the argument.
	 */
	public void setKeyRotationTime(Date time) {
		setKeyRotationTime(time.getTime() / 1000);
	}

	/**
	 * Returns the key rotation time, or null if unconfigured. See
	 * {@link #setKeyRotationTime(Date)} for a description of the field.
	 */
	public @Nullable Date getKeyRotationTime() {
		final long keyRotationTimestamp = vKeyRotationTimestamp;
		if (keyRotationTimestamp != 0)
			return new Date(keyRotationTimestamp * 1000);
		else
			return null;
	}

	/**
	 * <p>
	 * When a key rotation time is set, any money controlled by keys created before
	 * the given timestamp T will be automatically respent to any key that was
	 * created after T. This can be used to recover from a situation where a set of
	 * keys is believed to be compromised. You can stop key rotation by calling this
	 * method again with zero as the argument. Once set up, calling
	 * {@link #doMaintenance(org.spongycastle.crypto.params.KeyParameter, boolean)}
	 * will create and possibly send rotation transactions: but it won't be done
	 * automatically (because you might have to ask for the users password).
	 * </p>
	 *
	 * <p>
	 * The given time cannot be in the future.
	 * </p>
	 */
	public void setKeyRotationTime(long unixTimeSeconds) {
		checkArgument(unixTimeSeconds <= Utils.currentTimeSeconds(), "Given time (%s) cannot be in the future.",
				Utils.dateTimeFormat(unixTimeSeconds * 1000));
		vKeyRotationTimestamp = unixTimeSeconds;
		saveNow();
	}

	/**
	 * Returns whether the keys creation time is before the key rotation time, if
	 * one was set.
	 */
	public boolean isKeyRotating(ECKey key) {
		long time = vKeyRotationTimestamp;
		return time != 0 && key.getCreationTimeSeconds() < time;
	}

	// All Spend Candidates as List<TransactionOutput>
	public List<FreeStandingTransactionOutput> calculateAllSpendCandidates(KeyParameter aesKey, boolean multisigns)
			throws IOException {

		List<FreeStandingTransactionOutput> candidates = new ArrayList<FreeStandingTransactionOutput>();
		for (UTXO output : calculateAllSpendCandidatesUTXO(aesKey, multisigns)) {
			candidates.add(new FreeStandingTransactionOutput(this.params, output));
		}
		return candidates;

	}

	/*
	 * spendpending has timeout for 5 minute return false, if there is spendpending
	 * and timeout not
	 */
	public boolean checkSpendpending(UTXO output) throws IOException {
		if (output.isSpendPending()) {
			return (System.currentTimeMillis() - output.getSpendPendingTime()) > SPENTPENDINGTIMEOUT;
		}
		return true;

	}

	// All Spend Candidates as List<UTXO>
	public List<UTXO> calculateAllSpendCandidatesUTXO(KeyParameter aesKey, boolean multisigns) throws IOException {

		List<UTXO> candidates = new ArrayList<UTXO>();
		List<String> pubKeyHashs = new ArrayList<String>();
		for (ECKey ecKey : walletKeys(aesKey)) {
			pubKeyHashs.add(Utils.HEX.encode(ecKey.getPubKeyHash()));
		}
		byte[] response = OkHttp3Util.post(getServerURL() + ReqCmd.getOutputs.name(),
				Json.jsonmapper().writeValueAsString(pubKeyHashs).getBytes("UTF-8"));
		GetOutputsResponse getOutputsResponse = Json.jsonmapper().readValue(response, GetOutputsResponse.class);
		for (UTXO output : getOutputsResponse.getOutputs()) {
			if (checkSpendpending(output)) {
				if (multisigns) {
					candidates.add(output);
				} else {
					if (!output.isMultiSig()) {
						candidates.add(output);
					}
				}
			}
		}
		Collections.shuffle(candidates);
		return candidates;

	}

	public List<FreeStandingTransactionOutput> transforSpendCandidates(List<UTXO> outputs) {
		List<FreeStandingTransactionOutput> candidates = new ArrayList<FreeStandingTransactionOutput>();
		for (UTXO output : outputs) {
			candidates.add(new FreeStandingTransactionOutput(this.params, output));
		}
		return candidates;
	}

	public void substract(Map<String, Coin> valueInput, Map<String, Coin> valueOut) {

		for (Map.Entry<String, Coin> entry : valueInput.entrySet()) {

			Coin a = valueOut.get(entry.getKey());
			if (a != null) {
				valueInput.put(entry.getKey(), entry.getValue().subtract(a));
			}
		}
	}

	public String getServerURL() {
		if (serverPool == null) {
			serverPool = new ServerPool(params);
		}
		return serverPool.getServer().getServerurl();
	}

	public void setServerPool(ServerPool serverPool) {
		this.serverPool = serverPool;
	}

	public KeyChainGroup getKeyChainGroup() {
		return this.keyChainGroup;
	}

	/*
	 * get all keys in the wallet
	 */
	public List<ECKey> walletKeys(@Nullable KeyParameter aesKey) {
		DecryptingKeyBag maybeDecryptingKeyBag = new DecryptingKeyBag(this, aesKey);
		List<ECKey> walletKeys = new ArrayList<ECKey>();
		for (ECKey key : getImportedKeys()) {
			ECKey ecKey = maybeDecryptingKeyBag.maybeDecrypt(key);
			walletKeys.add(ecKey);
		}
		for (DeterministicKeyChain chain : getKeyChainGroup().getDeterministicKeyChains()) {
			for (ECKey key : chain.getLeafKeys()) {
				ECKey ecKey = maybeDecryptingKeyBag.maybeDecrypt(key);
				walletKeys.add(ecKey);
			}
		}
		return walletKeys;
	}

	public List<ECKey> walletKeys() {
		KeyParameter aesKey = null;
		return walletKeys(aesKey);
	}

	public HashMap<String, Address> getAddresses(KeyParameter aesKey) {

		HashMap<String, Address> addressResult = new HashMap<String, Address>();

		for (ECKey key : this.walletKeys(aesKey)) {
			String n = key.toAddress(this.getNetworkParameters()).toString();
			addressResult.put(n, key.toAddress(this.getNetworkParameters()));
		}

		return addressResult;
	}

	public boolean calculatedAddressHit(KeyParameter aesKey, String address) {

		for (ECKey key : this.walletKeys(aesKey)) {
			String n = key.toAddress(this.getNetworkParameters()).toString();
			if (n.equalsIgnoreCase(address)) {
				return true;
			}
		}

		return false;
	}

	public Block saveToken(TokenInfo tokenInfo, Coin basecoin, ECKey ownerKey, KeyParameter aesKey) throws Exception {
		return saveToken(tokenInfo, basecoin, ownerKey, aesKey, ownerKey.getPubKey(), new MemoInfo("coinbase"));
	}

	public Block saveToken(TokenInfo tokenInfo, Coin basecoin, ECKey ownerKey, KeyParameter aesKey, byte[] pubKeyTo,
			MemoInfo memoInfo) throws Exception {
		final Token token = tokenInfo.getToken();

		if (Utils.isBlank(token.getDomainNameBlockHash()) && Utils.isBlank(tokenInfo.getToken().getDomainName())) {
			final String domainname = token.getDomainName();
			GetDomainTokenResponse getDomainBlockHashResponse = this.getDomainNameBlockHash(domainname);
			Token domainNameBlockHash = getDomainBlockHashResponse.getdomainNameToken();
			token.setDomainNameBlockHash(domainNameBlockHash.getBlockHashHex());
			token.setDomainName(domainNameBlockHash.getTokenname());
		}

		if (Utils.isBlank(token.getDomainNameBlockHash()) && !Utils.isBlank(tokenInfo.getToken().getDomainName())) {
			Token domain = getDomainNameBlockHash(tokenInfo.getToken().getDomainName()).getdomainNameToken();
			token.setDomainNameBlockHash(domain.getBlockHashHex());

		}

		List<MultiSignAddress> multiSignAddresses = tokenInfo.getMultiSignAddresses();
		PermissionedAddressesResponse permissionedAddressesResponse = this.getPrevTokenMultiSignAddressList(token);
		if (permissionedAddressesResponse != null && permissionedAddressesResponse.getMultiSignAddresses() != null
				&& !permissionedAddressesResponse.getMultiSignAddresses().isEmpty()) {
			if (Utils.isBlank(token.getDomainName())) {
				token.setDomainName(permissionedAddressesResponse.getDomainName());
			}

			for (MultiSignAddress multiSignAddress : permissionedAddressesResponse.getMultiSignAddresses()) {
				final String pubKeyHex = multiSignAddress.getPubKeyHex();
				final String tokenid = token.getTokenid();
				multiSignAddresses.add(new MultiSignAddress(tokenid, "", pubKeyHex, 0));
			}
			// tokenInfo.setMultiSignAddresses(multiSignAddresses);
		}

		// +1 for domain name or super domain
		token.setSignnumber(token.getSignnumber() + 1);
		Block block = getTip();
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		block.addCoinbaseTransaction(pubKeyTo, basecoin, tokenInfo, memoInfo);

		Transaction transaction = block.getTransactions().get(0);

		Sha256Hash sighash = transaction.getHash();

		ECKey.ECDSASignature party1Signature = ownerKey.sign(sighash, aesKey);
		byte[] buf1 = party1Signature.encodeToDER();

		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(ownerKey.toAddress(params).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(ownerKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

		// add fee transaction
		if (getFee()) {
			block.addTransaction(feeTransaction(aesKey));
		}
		Block adjust = adjustSolveAndSign(block);
		return adjust;
	}

	private Block adjustSolveAndSign(Block block) throws IOException, JsonParseException, JsonMappingException {
		// save block
		try {

			block.solve();
			OkHttp3Util.post(getServerURL() + ReqCmd.signToken.name(), block.bitcoinSerialize());
			return block;
		} catch (ConnectException e) {
			serverConnectException();
			throw e;
		}

	}

	private void serverConnectException() {
		this.params.serverSeeds();
	}

	// pay the BIGTANGLE_TOKENID from the list HashMap<String, Long>
	// giveMoneyResult of
	// address and amount and return the remainder back to fromkey.
	// and repeat 3 times and wait as there may be a transaction pending for
	// this key

	public Block payMoneyToECKeyList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, String memo)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payMoneyToECKeyList(aesKey, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID, memo,
				calculateAllSpendCandidates(aesKey, false), 3, 60000);
	}

	public Block payMoneyToECKeyList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid,
			String memo) throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payMoneyToECKeyList(aesKey, giveMoneyResult, tokenid, memo, calculateAllSpendCandidates(aesKey, false),
				3, 60000);
	}

	public Block payMoneyToECKeyList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, String memo,
			List<FreeStandingTransactionOutput> coinList)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payMoneyToECKeyList(aesKey, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID, memo, coinList, 3,
				60000);
	}

	public Block payMoneyToECKeyList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid,
			String memo, List<FreeStandingTransactionOutput> coinList, int repeat, int sleep)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		try {
			return payToList(aesKey, giveMoneyResult, tokenid, memo, filterTokenid(tokenid, coinList));
		} catch (InsufficientMoneyException e) {
			log.debug(
					" InsufficientMoneyException  " + giveMoneyResult + " repeat time =" + repeat + " sleep=" + sleep);
			if (repeat > 0) {
				repeat -= 1;
				try {
					Thread.sleep(sleep);
				} catch (InterruptedException e1) {
				}
				return payMoneyToECKeyList(aesKey, giveMoneyResult, tokenid, memo,
						calculateAllSpendCandidates(aesKey, false), repeat, sleep);
			}
		} catch (RuntimeException e) {
			if (e.getMessage() != null && e.getMessage()
					.contains("net.bigtangle.core.exception.VerificationException$ConflictPossibleException")) {
				log.debug(e.getMessage() + "   " + giveMoneyResult + " repeat time =" + repeat + " sleep=" + sleep);
				if (repeat > 0) {
					repeat -= 1;
					try {
						Thread.sleep(sleep);
					} catch (InterruptedException e1) {
					}
					return payMoneyToECKeyList(aesKey, giveMoneyResult, tokenid, memo,
							calculateAllSpendCandidates(aesKey, false), repeat, sleep);
				}
			} else {
				throw e;
			}
		}

		throw new InsufficientMoneyException("InsufficientMoneyException " + giveMoneyResult);

	}

	// pay the tokenid from the list HashMap<String, Long> giveMoneyResult of
	// address and amount and return the remainder back to fromkey.
	public Block payToList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid,
			String memo) throws JsonProcessingException, IOException, InsufficientMoneyException {
		return payToList(aesKey, giveMoneyResult, tokenid, memo, calculateAllSpendCandidates(aesKey, false));
	}

	public List<Block> payFromList(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo)
			throws JsonProcessingException, IOException, InsufficientMoneyException {
		return payFromList(aesKey, destination, amount, memo, calculateAllSpendCandidates(aesKey, false));
	}

	public List<Block> payFromList(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo,
			List<FreeStandingTransactionOutput> coinList)
			throws JsonProcessingException, IOException, InsufficientMoneyException {
		return payFromList(aesKey, destination, amount, memo, coinList,
				NetworkParameters.TARGET_MAX_BLOCKS_IN_REWARD / 4);
	}

	private List<Block> payFromList(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo,
			List<FreeStandingTransactionOutput> coinList, int split)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		List<FreeStandingTransactionOutput> coinTokenList = filterTokenid(amount.getTokenid(), coinList);

		Coin sum = sum(coinTokenList);
		if (sum.compareTo(amount) < 0)
			throw new InsufficientMoneyException("to pay " + amount + " account sum: " + sum);
		// split the coinList into sub list, there is limit for transactions in a block
		// NetworkParameters.TARGET_MAX_BLOCKS_IN_REWARD / 4);
		List<List<FreeStandingTransactionOutput>> parts = chopped(coinTokenList, split);

		List<Block> re = new ArrayList<Block>();
		Coin payAmount = amount;
		for (int i = 0; i < parts.size(); i++) {
			Coin canPay = sum(parts.get(i));
			re.add(payFromListNoSplit(aesKey, destination, payAmount, memo, parts.get(i), getTip()));
			if (canPay.compareTo(payAmount) >= 0) {
				break;
			}
			payAmount = payAmount.subtract(canPay);
		}

		for (Block block : re) {
			if (getFee() && !amount.isBIG()) {
				// add big fee
				block.addTransaction(feeTransaction(aesKey, coinList));
			}
			log.debug(" " + block.toString());
			solveAndPost(block);
		}
		return re;
	}

	public Coin sum(List<FreeStandingTransactionOutput> coinList) {
		Coin sum = new Coin(0, coinList.get(0).getValue().getTokenid());
		for (FreeStandingTransactionOutput u : coinList) {
			sum = u.getValue().add(sum);
		}
		return sum;
	}

	// List<UTXO> coinList may not pay the amount, the rest to be paid is
	// restAmount
	private Block payFromListNoSplit(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo,
			List<FreeStandingTransactionOutput> coinList, Block tipBlock)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		Transaction multispent = payFromListNoSplitTransaction(aesKey, destination, amount, memo, coinList);
		tipBlock.addTransaction(multispent);

		return tipBlock;

	}

	private Transaction payFromListNoSplitTransaction(KeyParameter aesKey, String destination, Coin amount,
			MemoInfo memo, List<FreeStandingTransactionOutput> coinList) throws InsufficientMoneyException {
		Transaction multispent = new Transaction(params);
		multispent.setMemo(memo);
		multispent.addOutput(amount, Address.fromBase58(params, destination));
		Coin restAmount = amount.negate();
		ECKey beneficiary = null;
		if (getFee() && amount.isBIG()) {
			restAmount = restAmount.add(Coin.FEE_DEFAULT.negate());
		}

		List<FreeStandingTransactionOutput> coinTokenList = filterTokenid(restAmount.getTokenid(), coinList);

		for (FreeStandingTransactionOutput spendableOutput : coinTokenList) {

			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			restAmount = spendableOutput.getValue().add(restAmount);
			multispent.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!restAmount.isNegative()) {
				if (restAmount.isPositive()) {
					multispent.addOutput(restAmount, beneficiary);
				}
				break;
			}
		}
		if (beneficiary == null || restAmount.isNegative()) {
			throw new InsufficientMoneyException(amount.toString() + " outputs size= " + coinTokenList.size());
		}

		signTransaction(multispent, aesKey);
		return multispent;
	}

	public Block payToScript(KeyParameter aesKey, Coin amount, MemoInfo memo, Script script)
			throws InsufficientMoneyException, IOException {

		List<FreeStandingTransactionOutput> coinList = calculateAllSpendCandidates(aesKey, false);

		Transaction multispent = new Transaction(params);
		multispent.setMemo(memo);
		multispent.addOutput(amount, script);
		Coin restAmount = amount.negate();
		ECKey beneficiary = null;
		if (getFee() && amount.isBIG()) {
			restAmount = restAmount.add(Coin.FEE_DEFAULT.negate());
		}

		List<FreeStandingTransactionOutput> coinTokenList = filterTokenid(restAmount.getTokenid(), coinList);

		for (FreeStandingTransactionOutput spendableOutput : coinTokenList) {

			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			restAmount = spendableOutput.getValue().add(restAmount);
			multispent.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!restAmount.isNegative()) {
				if (restAmount.isPositive()) {
					multispent.addOutput(restAmount, beneficiary);
				}
				break;
			}
		}
		if (beneficiary == null || restAmount.isNegative()) {
			throw new InsufficientMoneyException(amount.toString() + " outputs size= " + coinTokenList.size());
		}

		signTransaction(multispent, aesKey);

		Block b = getTip();
		b.addTransaction(multispent);
		if (getFee() && !amount.isBIG()) {
			// add big fee
			b.addTransaction(feeTransaction(aesKey, coinList));
		}
		log.debug(" " + b.toString());
		solveAndPost(b);
		return b;
	}

	public Block getTip() throws IOException, JsonProcessingException {
		return params.getDefaultSerializer().makeBlock(getTipData());
	}

	private byte[] getTipData() throws IOException, JsonProcessingException {
		HashMap<String, String> requestParam = new HashMap<String, String>();
		return OkHttp3Util.postAndGetBlock(getServerURL() + ReqCmd.getTip,
				Json.jsonmapper().writeValueAsString(requestParam));
	}

	// chops a list into non-view sublists of length L
	public static <T> List<List<T>> chopped(List<T> list, final int L) {
		List<List<T>> parts = new ArrayList<List<T>>();
		final int N = list.size();
		for (int i = 0; i < N; i += L) {
			parts.add(new ArrayList<T>(list.subList(i, Math.min(N, i + L))));
		}
		return parts;
	}

	public Block payToList(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid,
			String memo, List<FreeStandingTransactionOutput> coinList)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		if (giveMoneyResult.isEmpty()) {
			return null;
		}
		Transaction multispent = payToListTransaction(aesKey, giveMoneyResult, tokenid, memo, coinList);

		Block block = getTip();
		block.addTransaction(multispent);
		if (getFee() && !NetworkParameters.BIGTANGLE_TOKENID.equals(tokenid)) {
			block.addTransaction(feeTransaction(aesKey, coinList));
		}
		return solveAndPost(block);
	}

	public Transaction payToListTransaction(KeyParameter aesKey, HashMap<String, BigInteger> giveMoneyResult,
			byte[] tokenid, String memo, List<FreeStandingTransactionOutput> coinList)
			throws InsufficientMoneyException {
		Coin summe = Coin.valueOf(0, tokenid);
		Transaction multispent = new Transaction(params);
		multispent.setMemo(new MemoInfo(memo));
		for (Map.Entry<String, BigInteger> entry : giveMoneyResult.entrySet()) {
			Coin a = new Coin(entry.getValue(), tokenid);
			Address address = Address.fromBase58(params, entry.getKey());
			multispent.addOutput(a, address);
			summe = summe.add(a);
		}
		Coin amount = summe.negate();

		if (getFee() && amount.isBIG()) {
			amount = amount.add(Coin.FEE_DEFAULT.negate());
		}

		ECKey beneficiary = null;
		// filter only for tokenid
		List<FreeStandingTransactionOutput> coinListTokenid = filterTokenid(tokenid, coinList);
		for (FreeStandingTransactionOutput spendableOutput : coinListTokenid) {
			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			amount = spendableOutput.getValue().add(amount);
			multispent.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!amount.isNegative()) {
				multispent.addOutput(amount, beneficiary);
				break;
			}
		}
		if (beneficiary == null || amount.isNegative()) {
			throw new InsufficientMoneyException(summe.toString() + " outputs size= " + coinListTokenid.size());
		}

		signTransaction(multispent, aesKey);
		return multispent;
	}

	public Transaction feeTransaction(KeyParameter aesKey) throws InsufficientMoneyException, IOException {
		return feeTransaction(aesKey, calculateAllSpendCandidates(aesKey, false));
	}

	public Transaction feeTransaction(KeyParameter aesKey, List<FreeStandingTransactionOutput> coinList)
			throws InsufficientMoneyException {

		Transaction spent = new Transaction(params);
		spent.setMemo(new MemoInfo("fee"));
		// Fixed fee in BIG
		Coin amount = Coin.FEE_DEFAULT.negate();
		ECKey beneficiary = null;
		// filter only for NetworkParameters.BIGTANGLE_TOKENID
		List<FreeStandingTransactionOutput> coinListTokenid = filterTokenid(NetworkParameters.BIGTANGLE_TOKENID,
				coinList);
		for (FreeStandingTransactionOutput spendableOutput : coinListTokenid) {
			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			amount = spendableOutput.getValue().add(amount);
			spent.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!amount.isNegative()) {
				spent.addOutput(amount, beneficiary);
				break;
			}
		}
		if (beneficiary == null || amount.isNegative()) {
			throw new InsufficientMoneyException(
					Coin.FEE_DEFAULT.toString() + " outputs size= " + coinListTokenid.size());
		}

		signTransaction(spent, aesKey);
		return spent;
	}

	// check the token id is on the server
	// throw NoTokenException
	public Token checkTokenId(String tokenid) throws JsonProcessingException, IOException, NoTokenException {
		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		GetTokensResponse token = Json.jsonmapper().readValue(resp, GetTokensResponse.class);
		if (token.getTokens() == null || token.getTokens().isEmpty()) {
			throw new NoTokenException();
		}
		return token.getTokens().get(0);
	}

	/*
	 * It must use BigInteger to calculation to avoid overflow. Order can handle
	 * only Long
	 */
	public BigInteger totalAmount(long price, long amount, int tokenDecimal, boolean allowRemainder) {

		BigInteger[] rearray = BigInteger.valueOf(price).multiply(BigInteger.valueOf(amount))
				.divideAndRemainder(BigInteger.valueOf(LongMath.checkedPow(10, tokenDecimal)));
		BigInteger re = rearray[0];
		BigInteger remainder = rearray[1];
		if (remainder.compareTo(BigInteger.ZERO) > 0 && !allowRemainder) {
			// This remainder will cut
			throw new OrderWithRemainderException("Invalid price and quantity value with remainder " + remainder);
		}
		if (re.compareTo(BigInteger.ONE) < 0 || re.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
			throw new InvalidTransactionDataException("Invalid target total value: " + re);
		}
		return re;
	}

	/*
	 * Buy order is defined as offervalue = targetValue * price / 10**targetDecimal
	 * offerToken=orderBaseToken
	 * 
	 */
	public Block buyOrder(KeyParameter aesKey, String targetTokenId, long buyPrice, long targetValue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder)
			throws JsonProcessingException, IOException, InsufficientMoneyException, NoTokenException {
		Token targetToken = checkTokenId(targetTokenId);
		return buyOrder(aesKey, targetToken, buyPrice, targetValue, validToTime, validFromTime, orderBaseToken,
				allowRemainder);
	}

	public Block buyOrder(KeyParameter aesKey, Token targetToken, long buyPrice, long targetValue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder)
			throws JsonProcessingException, IOException, InsufficientMoneyException, NoTokenException {

		if (targetToken.getTokenid().equals(orderBaseToken))
			throw new OrderImpossibleException("buy token is base token ");

		List<FreeStandingTransactionOutput> candidates = calculateAllSpendCandidates(aesKey, false);

		return buyOrder(aesKey, targetToken, buyPrice, targetValue, validToTime, validFromTime, orderBaseToken,
				allowRemainder, candidates);
	}

	public Block buyOrder(KeyParameter aesKey, Token targetToken, long buyPrice, long targetValue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates)
			throws JsonProcessingException, IOException, InsufficientMoneyException, NoTokenException {

		return buyOrderDo(aesKey, targetToken, buyPrice, targetValue, validToTime, validFromTime, orderBaseToken,
				allowRemainder, candidates, 3, 60000);
	}

	public Block buyOrderDo(KeyParameter aesKey, Token targetToken, long buyPrice, long targetValue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates, int repeat, int sleep)
			throws JsonProcessingException, IOException, InsufficientMoneyException, NoTokenException {
		try {
			return buyOrderDo(aesKey, targetToken, buyPrice, targetValue, validToTime, validFromTime, orderBaseToken,
					allowRemainder, candidates);
		} catch (RuntimeException e) {
			if (e.getMessage().contains("ConflictPossibleException:")) {
				log.debug(" ConflictPossibleException  " + " repeat time =" + repeat + " sleep=" + sleep);

				if (repeat > 0) {
					repeat -= 1;
					try {
						Thread.sleep(sleep);
					} catch (InterruptedException e1) {
					}
					candidates = calculateAllSpendCandidates(aesKey, false);
					return buyOrderDo(aesKey, targetToken, buyPrice, targetValue, validToTime, validFromTime,
							orderBaseToken, allowRemainder, candidates, repeat, sleep);

				}
			} else {
				throw e;
			}
		}
		throw new InsufficientMoneyException("payTransaction ");
	}

	public Block buyOrderDo(KeyParameter aesKey, Token targetToken, long buyPrice, long targetValue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates)
			throws JsonProcessingException, IOException, InsufficientMoneyException, NoTokenException {

		if (targetToken.getTokenid().equals(orderBaseToken))
			throw new OrderImpossibleException("buy token is base token ");
		Integer priceshift = params.getOrderPriceShift(orderBaseToken);
		// Burn orderBaseToken to buy
		Coin toBePaid = new Coin(
				totalAmount(buyPrice, targetValue, targetToken.getDecimals() + priceshift, allowRemainder),
				Utils.HEX.decode(orderBaseToken)).negate();
		if (getFee() && NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(orderBaseToken)) {
			toBePaid = toBePaid.add(Coin.FEE_DEFAULT.negate());
		}
		Transaction tx = new Transaction(params);

		ECKey beneficiary = null;

		for (FreeStandingTransactionOutput spendableOutput : candidates) {
			if (orderBaseToken.equals(spendableOutput.getUTXO().getTokenId())) {
				beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
				toBePaid = spendableOutput.getValue().add(toBePaid);
				tx.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
				if (!toBePaid.isNegative()) {
					tx.addOutput(toBePaid, beneficiary);
					break;
				}
			}
		}
		if (beneficiary == null || toBePaid.isNegative()) {
			throw new InsufficientMoneyException("" + orderBaseToken);
		}

		OrderOpenInfo info = new OrderOpenInfo(targetValue, targetToken.getTokenid(), beneficiary.getPubKey(),
				validToTime, validFromTime, Side.BUY, beneficiary.toAddress(params).toBase58(), orderBaseToken,
				buyPrice,
				totalAmount(buyPrice, targetValue, targetToken.getDecimals() + priceshift, allowRemainder).longValue(),
				orderBaseToken);
		tx.setData(info.toByteArray());
		tx.setDataClassName("OrderOpen");
		signTransaction(tx, aesKey);
		Block block = getTip();

		block.addTransaction(tx);
		block.setBlockType(Type.BLOCKTYPE_ORDER_OPEN);

		if (getFee() && !NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(orderBaseToken)) {
			block.addTransaction(feeTransaction(aesKey, candidates));
		}

		return solveAndPost(block);
	}

	/*
	 * Sell Order is defined as targetvalue = offervalue * price / 10**offerDecimal
	 * targetToken=orderBaseToken
	 */
	public Block sellOrder(KeyParameter aesKey, String offerTokenId, long sellPrice, long offervalue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder)
			throws JsonProcessingException, IOException, NoTokenException, InsufficientMoneyException {
		Token t = checkTokenId(offerTokenId);
		return sellOrder(aesKey, t, sellPrice, offervalue, validToTime, validFromTime, orderBaseToken, allowRemainder);
	}

	public Block sellOrder(KeyParameter aesKey, Token t, long sellPrice, long offervalue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder)
			throws IOException, InsufficientMoneyException, NoTokenException {
		if (t.getTokenid().equals(orderBaseToken))
			throw new OrderImpossibleException("sell token is not allowed as base token ");

		List<FreeStandingTransactionOutput> candidates = calculateAllSpendCandidates(aesKey, false);

		return sellOrder(aesKey, t, sellPrice, offervalue, validToTime, validFromTime, orderBaseToken, allowRemainder,
				candidates);
	}

	public Block sellOrder(KeyParameter aesKey, Token t, long sellPrice, long offervalue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates)
			throws IOException, InsufficientMoneyException, NoTokenException {
		return sellOrderDo(aesKey, t, sellPrice, offervalue, validToTime, validFromTime, orderBaseToken, allowRemainder,
				candidates, 3, 60000);
	}

	public Block sellOrderDo(KeyParameter aesKey, Token t, long sellPrice, long offervalue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates, int repeat, int sleep)
			throws IOException, InsufficientMoneyException, NoTokenException {
		try {
			return sellOrderDo(aesKey, t, sellPrice, offervalue, validToTime, validFromTime, orderBaseToken,
					allowRemainder, candidates);
		} catch (RuntimeException e) {
			if (e.getMessage().contains("ConflictPossibleException:")) {
				log.debug(" ConflictPossibleException  " + " repeat time =" + repeat + " sleep=" + sleep);

				if (repeat > 0) {
					repeat -= 1;
					try {
						Thread.sleep(sleep);
					} catch (InterruptedException e1) {
					}
					candidates = calculateAllSpendCandidates(aesKey, false);
					return sellOrderDo(aesKey, t, sellPrice, offervalue, validToTime, validFromTime, orderBaseToken,
							allowRemainder, candidates, repeat, sleep);

				}
			} else {

				throw e;
			}
		}
		throw new InsufficientMoneyException("payTransaction ");
	}

	public Block sellOrderDo(KeyParameter aesKey, Token t, long sellPrice, long offervalue, Long validToTime,
			Long validFromTime, String orderBaseToken, boolean allowRemainder,
			List<FreeStandingTransactionOutput> candidates)
			throws IOException, InsufficientMoneyException, NoTokenException {
		if (t.getTokenid().equals(orderBaseToken))
			throw new OrderImpossibleException("sell token is not allowed as base token ");
		Integer priceshift = params.getOrderPriceShift(orderBaseToken);
		// Burn tokens to sell
		Coin myCoin = Coin.valueOf(offervalue, t.getTokenid()).negate();

		if (getFee() && NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(t.getTokenid())) {
			myCoin = myCoin.add(Coin.FEE_DEFAULT.negate());
		}

		Transaction tx = new Transaction(params);

		ECKey beneficiary = null;
		for (FreeStandingTransactionOutput spendableOutput : candidates) {
			if (t.getTokenid().equals(spendableOutput.getUTXO().getTokenId())) {
				beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
				myCoin = spendableOutput.getValue().add(myCoin);
				tx.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
				if (!myCoin.isNegative()) {
					tx.addOutput(myCoin, beneficiary);
					break;
				}
			}
		}
		if (beneficiary == null || myCoin.isNegative()) {
			throw new InsufficientMoneyException("");
		}
		// get the base token
		BigInteger targetvalue = totalAmount(sellPrice, offervalue, t.getDecimals() + priceshift, allowRemainder);
		if (targetvalue.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
			throw new InvalidTransactionDataException("Invalid  max: " + targetvalue + " > " + Long.MAX_VALUE);
		}

		OrderOpenInfo info = new OrderOpenInfo(targetvalue.longValue(), orderBaseToken, beneficiary.getPubKey(),
				validToTime, validFromTime, Side.SELL, beneficiary.toAddress(params).toBase58(), orderBaseToken,
				sellPrice, offervalue, t.getTokenid());
		tx.setData(info.toByteArray());
		tx.setDataClassName("OrderOpen");

		signTransaction(tx, aesKey);
		Block block = getTip();
		block.addTransaction(tx);
		block.setBlockType(Type.BLOCKTYPE_ORDER_OPEN);
		if (getFee() && !NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(t.getTokenid())) {
			block.addTransaction(feeTransaction(aesKey, candidates));
		}
		return solveAndPost(block);
	}

	public Block cancelOrder(Sha256Hash orderblockhash, KeyParameter aesKey, String address)
			throws JsonProcessingException, IOException, InsufficientMoneyException, NoDataException {
		ECKey legitimatingKey = null;
		for (ECKey ecKey : walletKeys(aesKey)) {
			if (address.equals(ecKey.toAddress(params).toString())) {
				legitimatingKey = ecKey;
				break;
			}
		}
		if (legitimatingKey == null) {
			throw new NoDataException(" no keys ");
		}
		// Make an order op
		Transaction tx = new Transaction(params);
		OrderCancelInfo info = new OrderCancelInfo(orderblockhash);
		tx.setData(info.toByteArray());

		// Legitimate it by signing
		Sha256Hash sighash1 = tx.getHash();
		ECKey.ECDSASignature party1Signature = legitimatingKey.sign(sighash1, null);
		byte[] buf1 = party1Signature.encodeToDER();
		tx.setDataSignature(buf1);

		Block block = getTip();

		block.addTransaction(tx);
		block.setBlockType(Type.BLOCKTYPE_ORDER_CANCEL);
		if (getFee())
			block.addTransaction(feeTransaction(aesKey, calculateAllSpendCandidates(aesKey, false)));
		return solveAndPost(block);

	}

	public Block contractEventCancel(Sha256Hash eventblockhash, KeyParameter aesKey, String address)
			throws JsonProcessingException, IOException, InsufficientMoneyException, NoDataException {
		ECKey legitimatingKey = null;
		for (ECKey ecKey : walletKeys(aesKey)) {
			if (address.equals(ecKey.toAddress(params).toString())) {
				legitimatingKey = ecKey;
				break;
			}
		}
		if (legitimatingKey == null) {
			throw new NoDataException(" no keys ");
		}
		// Make an order op
		Transaction tx = new Transaction(params);
		ContractEventCancelInfo info = new ContractEventCancelInfo(eventblockhash);
		tx.setData(info.toByteArray());

		// Legitimate it by signing
		Sha256Hash sighash1 = tx.getHash();
		ECKey.ECDSASignature party1Signature = legitimatingKey.sign(sighash1, null);
		byte[] buf1 = party1Signature.encodeToDER();
		tx.setDataSignature(buf1);

		Block block = getTip();

		block.addTransaction(tx);
		block.setBlockType(Type.BLOCKTYPE_CONTRACTEVENT_CANCEL);
		if (getFee())
			block.addTransaction(feeTransaction(aesKey, calculateAllSpendCandidates(aesKey, false)));
		return solveAndPost(block);

	}

	public Block payContract(KeyParameter aesKey, String tokenId, BigInteger payAmount, Long validToTime,
			Long validFromTime, String contractTokenid)
			throws JsonProcessingException, IOException, InsufficientMoneyException, NoTokenException {
		// add client check if the tokenid exists
		// Token t = checkTokenId(tokenId);
		// Burn BIG to buy

		Coin amount = new Coin(payAmount, tokenId).negate();

		if (getFee() && NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(tokenId)) {
			amount = amount.add(Coin.FEE_DEFAULT.negate());
		}

		Transaction tx = new Transaction(params);
		List<FreeStandingTransactionOutput> coinList = calculateAllSpendCandidates(aesKey, false);
		ECKey beneficiary = null;
		for (FreeStandingTransactionOutput spendableOutput : filterTokenid(amount.getTokenid(), coinList)) {

			beneficiary = getECKey(aesKey, spendableOutput.getUTXO().getAddress());
			amount = spendableOutput.getValue().add(amount);
			tx.addInput(spendableOutput.getUTXO().getBlockHash(), spendableOutput);
			if (!amount.isNegative()) {
				tx.addOutput(amount, beneficiary);
				break;
			}
		}
		if (beneficiary == null || amount.isNegative()) {
			throw new InsufficientMoneyException(amount.toString() + " outputs size= " + coinList.size());

		}

		ContractEventInfo info = new ContractEventInfo(contractTokenid, payAmount, tokenId,
				beneficiary.toAddress(params).toBase58(), validToTime, validFromTime, "");
		tx.setData(info.toByteArray());
		tx.setDataClassName("ContractEventInfo");
		signTransaction(tx, aesKey);
		Block block = getTip();
		block.addTransaction(tx);
		block.setBlockType(Type.BLOCKTYPE_CONTRACT_EVENT);

		if (getFee() && !NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(tokenId)) {
			block.addTransaction(feeTransaction(aesKey, coinList));
		}
		return solveAndPost(block);
	}

	public Block solveAndPost(Block block) throws IOException {
		try {
			block.solve();
			// check the valid to time must be at least the block creation time

			OkHttp3Util.post(getServerURL() + ReqCmd.saveBlock.name(), block.bitcoinSerialize());
			return block;
		} catch (ConnectException e) {
			this.serverPool.removeServer(getServerURL());
			throw e;
		}

	}

	private List<FreeStandingTransactionOutput> filterTokenid(byte[] tokenid, List<FreeStandingTransactionOutput> l) {
		List<FreeStandingTransactionOutput> re = new ArrayList<FreeStandingTransactionOutput>();
		for (FreeStandingTransactionOutput u : l) {
			if (Arrays.equals(u.getValue().getTokenid(), tokenid)) {
				re.add(u);
			}
		}
		return re;
	}

	public Block paySubtangle(KeyParameter aesKey, String outputStr, ECKey connectKey, Address toAddressInSubtangle,
			Coin coin, Address address) throws JsonProcessingException, IOException {

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("hexStr", outputStr);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getOutputByKey.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		OutputsDetailsResponse outputsDetailsResponse = Json.jsonmapper().readValue(resp, OutputsDetailsResponse.class);
		UTXO findOutput = outputsDetailsResponse.getOutputs();

		TransactionOutput spendableOutput = new FreeStandingTransactionOutput(params, findOutput);
		Transaction transaction = new Transaction(params);

		transaction.addOutput(coin, address);

		transaction.setToAddressInSubtangle(toAddressInSubtangle.getHash160());

		TransactionInput input = transaction.addInput(findOutput.getBlockHash(), spendableOutput);
		Sha256Hash sighash = transaction.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL,
				false);

		TransactionSignature tsrecsig = new TransactionSignature(connectKey.sign(sighash, aesKey),
				Transaction.SigHash.ALL, false);
		Script inputScript = ScriptBuilder.createInputScript(tsrecsig);
		input.setScriptSig(inputScript);
		Block block = getTip();
		block.addTransaction(transaction);

		return solveAndPost(block);
	}

	public ECKey getECKey(KeyParameter aesKey, String address) {

		List<ECKey> keys = walletKeys(aesKey);
		ECKey beneficiary = null;
		for (ECKey ecKey : keys) {
			if (address.equals(ecKey.toAddress(params).toString())) {
				beneficiary = ecKey;
				return beneficiary;
			}
		}
		throw new RuntimeException("no key in wallet is found for this address " + address);
	}

	public List<Block> pay(KeyParameter aesKey, String destination, Coin amount, String memo)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payFromList(aesKey, destination.toString(), amount, new MemoInfo(memo));
	}

	public List<Block> pay(KeyParameter aesKey, Address destination, Coin amount, String memo)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payFromList(aesKey, destination.toString(), amount, new MemoInfo(memo));
	}

	public List<Block> pay(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payFromList(aesKey, destination, amount, memo);
	}

	public Transaction createTransaction(KeyParameter aesKey, String destination, Coin amount, MemoInfo memo)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payFromListNoSplitTransaction(aesKey, destination, amount, memo,
				calculateAllSpendCandidates(aesKey, false));
	}

	public Transaction createTransaction(KeyParameter aesKey, List<FreeStandingTransactionOutput> candidates,
			String destination, Coin amount, String memo)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payFromListNoSplitTransaction(aesKey, destination, amount, new MemoInfo(memo), candidates);
	}

	public Transaction createTransaction(KeyParameter aesKey, List<FreeStandingTransactionOutput> candidates,
			Address destination, Coin amount, String memo)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payFromListNoSplitTransaction(aesKey, destination.toString(), amount, new MemoInfo(memo), candidates);
	}

//no repeat here
	public Block payTransaction(List<Transaction> txs)
			throws JsonProcessingException, IOException, InsufficientMoneyException {
		Block block = getTip();
		for (Transaction tx : txs) {
			block.addTransaction(tx);
		}
		return solveAndPost(block);
	}

	/*
	 * pay all small coins in a wallet to one destination. This destination can be
	 * in same wallet.
	 */
	public List<Block> payPartsToOne(KeyParameter aesKey, String destination, byte[] tokenid, String memo)
			throws JsonProcessingException, IOException, InsufficientMoneyException {

		return payPartsToOne(aesKey, destination, tokenid, memo, BigInteger.ZERO);
	}

	/*
	 * pay all small coins in a wallet to one destination. This destination can be
	 * in same wallet.
	 */
	public List<Block> payPartsToOne(KeyParameter aesKey, String destination, byte[] tokenid, String memo,
			BigInteger low) throws JsonProcessingException, IOException, InsufficientMoneyException {

		List<UTXO> l = calculateAllSpendCandidatesUTXO(aesKey, false);

		List<FreeStandingTransactionOutput> candidates = new ArrayList<FreeStandingTransactionOutput>();

		Coin summe = Coin.valueOf(0, tokenid);
		int size = 0;
		for (UTXO u : l) {
			if (Arrays.equals(u.getValue().getTokenid(), tokenid)
					&& size < NetworkParameters.MAX_DEFAULT_BLOCK_SIZE / 10000) {
				if (low.signum() == 0 || (low.signum() > 0 && u.getValue().getValue().compareTo(low) > 0)) {
					summe = summe.add(u.getValue());

					candidates.add(new FreeStandingTransactionOutput(this.params, u));

					size += 1;
				}
			}
		}
		//
		if (getFee() && NetworkParameters.BIGTANGLE_TOKENID.equals(tokenid)) {
			summe = summe.subtract(Coin.FEE_DEFAULT);
		}
		return pay(aesKey, destination, summe, new MemoInfo(memo));
	}

	public Block saveUserdata(ECKey userKey, Transaction transaction, boolean encrypt, KeyParameter aesKey)
			throws JsonProcessingException, IOException, InsufficientMoneyException, InvalidCipherTextException {
		// transaction.getData() is not encrypted
		if (encrypt) {
			byte[] cipher = ECIESCoder.encrypt(userKey.getPubKeyPoint(), transaction.getData());
			transaction.setData(cipher);
		}
		Block block = getTip();

		Sha256Hash sighash = transaction.getHash();
		ECKey.ECDSASignature party1Signature = userKey.sign(sighash);
		byte[] buf1 = party1Signature.encodeToDER();

		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setAddress(userKey.toAddress(params).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(userKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignBies));
		block.addTransaction(transaction);
		if (getFee()) {
			block.addTransaction(feeTransaction(aesKey));
		}
		block.setBlockType(Type.BLOCKTYPE_USERDATA);
		return solveAndPost(block);
	}

	public UserSettingDataInfo getUserSettingDataInfo(ECKey userKey, boolean encrypt)
			throws JsonProcessingException, IOException, InvalidCipherTextException {
		HashMap<String, String> requestParam0 = new HashMap<String, String>();
		requestParam0.put("dataclassname", DataClassName.UserSettingDataInfo.name());
		requestParam0.put("pubKey", Utils.HEX.encode(userKey.getPubKey()));
		byte[] buf = OkHttp3Util.postAndGetBlock(getServerURL() + ReqCmd.getUserData.name(),
				Json.jsonmapper().writeValueAsString(requestParam0));
		UserSettingDataInfo userSettingDataInfo = null;
		if (buf != null && buf.length > 0) {
			if (encrypt) {
				byte[] decryptedPayload = ECIESCoder.decrypt(userKey.getPrivKey(), buf);
				userSettingDataInfo = new UserSettingDataInfo().parse(decryptedPayload);
			} else {
				userSettingDataInfo = new UserSettingDataInfo().parse(buf);
			}

		}
		return userSettingDataInfo;

	}

	public void publishDomainName(ECKey ownerKey, String tokenid, String tokenname, KeyParameter aesKey,
			String description) throws Exception {
		GetDomainTokenResponse getDomainBlockHashResponse = this.getDomainNameBlockHash(tokenname);
		Token domainName = getDomainBlockHashResponse.getdomainNameToken();

		List<ECKey> walletKeys = new ArrayList<ECKey>();
		walletKeys.add(ownerKey);

		final int signnumber = walletKeys.size();
		this.publishDomainName(walletKeys, ownerKey, tokenid, tokenname, domainName, aesKey, description, signnumber);
	}

	public void publishDomainName(List<ECKey> signKeys, ECKey ownerKey, String tokenid, String tokenname,
			KeyParameter aesKey, BigInteger amount, String description) throws Exception {
		GetDomainTokenResponse getDomainBlockHashResponse = this.getDomainNameBlockHash(tokenname);
		Token domainNameBlockHash = getDomainBlockHashResponse.getdomainNameToken();
		final int signnumber = signKeys.size();
		this.publishDomainName(signKeys, ownerKey, tokenid, tokenname, domainNameBlockHash, aesKey, description,
				signnumber);
	}

	public void publishDomainName(List<ECKey> multiSigns, ECKey ownerKey, String tokenid, String tokenname,
			Token domainNameBlockHash, KeyParameter aesKey, String description, int signnumber) throws Exception {

		TokenIndexResponse tokenIndexResponse = this.getServerCalTokenIndex(tokenid);

		long tokenindex_ = tokenIndexResponse.getTokenindex();

		Token tokens = Token.buildDomainnameTokenInfo(true, tokenIndexResponse.getBlockhash(), tokenid, tokenname,
				description, signnumber, tokenindex_, false, domainNameBlockHash.getTokenname(),
				domainNameBlockHash.getBlockHashHex());
		TokenInfo tokenInfo = new TokenInfo();
		tokenInfo.setToken(tokens);

		List<MultiSignAddress> multiSignAddresses = new ArrayList<MultiSignAddress>();
		tokenInfo.setMultiSignAddresses(multiSignAddresses);

		for (ECKey ecKey : multiSigns) {
			multiSignAddresses.add(new MultiSignAddress(tokenid, "", ecKey.getPublicKeyAsHex()));
		}

		saveToken(tokenInfo, Coin.valueOf(1, tokenid), ownerKey, aesKey, ownerKey.getPubKey(),
				new MemoInfo("publishDomainName"));

	}

	public TokenIndexResponse getServerCalTokenIndex(String tokenid) throws Exception {
		HashMap<String, String> requestParam = new HashMap<String, String>();
		requestParam.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp, TokenIndexResponse.class);
		return tokenIndexResponse;
	}

	public PermissionedAddressesResponse getPrevTokenMultiSignAddressList(Token token) throws Exception {
		HashMap<String, String> requestParam = new HashMap<String, String>();
		requestParam.put("domainNameBlockHash", token.getDomainNameBlockHash());
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenPermissionedAddresses.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		PermissionedAddressesResponse permissionedAddressesResponse = Json.jsonmapper().readValue(resp,
				PermissionedAddressesResponse.class);
		return permissionedAddressesResponse;
	}

	public GetDomainTokenResponse getDomainNameBlockHash(String domainname) throws Exception {
		return getDomainNameBlockHash(domainname, "");
	}

	public GetDomainTokenResponse getDomainNameBlockHash(String domainname, String token) throws Exception {
		HashMap<String, String> requestParam = new HashMap<String, String>();
		requestParam.put("domainname", domainname);
		requestParam.put("token", token);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getDomainNameBlockHash.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetDomainTokenResponse getDomainBlockHashResponse = Json.jsonmapper().readValue(resp,
				GetDomainTokenResponse.class);
		return getDomainBlockHashResponse;
	}

	public Block multiSign(final String tokenid, ECKey outKey, KeyParameter aesKey) throws Exception {
		HashMap<String, Object> requestParam = new HashMap<String, Object>();

		String address = outKey.toAddress(params).toBase58();
		requestParam.put("address", address);
		requestParam.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenSignByAddress.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);
		if (multiSignResponse.getMultiSigns() == null || multiSignResponse.getMultiSigns().isEmpty())
			return null;
		MultiSign multiSign = multiSignResponse.getMultiSigns().get(0);

		byte[] payloadBytes = Utils.HEX.decode((String) multiSign.getBlockhashHex());
		Block block = params.getDefaultSerializer().makeBlock(payloadBytes);
		// replace block prototype if it is too too old

		Transaction transaction = block.getTransactions().get(0);

		List<MultiSignBy> multiSignBies = null;
		if (transaction.getDataSignature() == null) {
			multiSignBies = new ArrayList<MultiSignBy>();
		} else {
			MultiSignByRequest multiSignByRequest = Json.jsonmapper().readValue(transaction.getDataSignature(),
					MultiSignByRequest.class);
			multiSignBies = multiSignByRequest.getMultiSignBies();
		}
		Sha256Hash sighash = transaction.getHash();
		ECKey.ECDSASignature party1Signature = outKey.sign(sighash, aesKey);
		byte[] buf1 = party1Signature.encodeToDER();

		MultiSignBy multiSignBy0 = new MultiSignBy();

		multiSignBy0.setTokenid(multiSign.getTokenid());
		multiSignBy0.setTokenindex(multiSign.getTokenindex());
		multiSignBy0.setAddress(outKey.toAddress(params).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

		return adjustSolveAndSign(checkBlockPrototype(block));
	}

	private Block checkBlockPrototype(Block oldBlock) throws BlockStoreException, NoBlockException, IOException {

		int time = 60 * 60 * 8;
		if (System.currentTimeMillis() / 1000 - oldBlock.getTimeSeconds() > time) {
			Block block = getTip();
			block.setBlockType(oldBlock.getBlockType());
			for (Transaction transaction : oldBlock.getTransactions()) {
				block.addTransaction(transaction);
			}
			block.solve();
			return block;
		} else {
			return oldBlock;
		}
	}

	public Long calc(long m, long factor, long d) {
		return BigInteger.valueOf(m).multiply(BigInteger.valueOf(factor)).divide(BigInteger.valueOf(d)).longValue();
	}

	public Block createToken(ECKey key, String domainname, boolean increment, Token token,
			List<MultiSignAddress> addresses) throws Exception {
		return createToken(key, domainname, increment, token, addresses, key.getPubKey(), new MemoInfo("coinbase"));
	}

	public Block createToken(ECKey key, String domainname, boolean increment, Token token,
			List<MultiSignAddress> addresses, byte[] pubkeyTo, MemoInfo memoInfo) throws Exception {
		Token domain = getDomainNameBlockHash(domainname, "token").getdomainNameToken();
		token.setDomainName(domain.getTokenname());
		token.setDomainNameBlockHash(domain.getBlockHashHex());

		String tokenid = token.getTokenid();
		// key.getPublicKeyAsHex();

		HashMap<String, String> requestParam00 = new HashMap<String, String>();
		requestParam00.put("tokenid", tokenid);
		byte[] resp2 = OkHttp3Util.postString(getServerURL() + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam00));
		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);

		token.setTokenindex(tokenIndexResponse.getTokenindex());
		token.setPrevblockhash(tokenIndexResponse.getBlockhash());
		token.setTokenstop(!increment);
		TokenInfo tokenInfo = new TokenInfo();
		// tokens.setTokentype(TokenType.currency.ordinal());
		tokenInfo.setToken(token);
		tokenInfo.setMultiSignAddresses(addresses);
		// tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid,
		// "", key.getPublicKeyAsHex()));
		return saveToken(tokenInfo, new Coin(token.getAmount(), tokenid), key, null, pubkeyTo, memoInfo);
	}

	public Block createToken(ECKey key, String tokename, int decimals, String domainname, String description,
			BigInteger amount, boolean increment, KeyValue kv, int tokentype, List<MultiSignAddress> addresses,
			String tokenid) throws Exception {

		Token token = Token.buildSimpleTokenInfo(true, Sha256Hash.ZERO_HASH, tokenid, tokename, description, 1, 0,
				amount, !increment, decimals, "");
		token.addKeyvalue(kv);
		token.setTokentype(tokentype);

		return createToken(key, domainname, increment, token, addresses);

	}

	public Block getBlock(String hashHex) throws JsonProcessingException, IOException {

		Map<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("hashHex", hashHex);

		byte[] data = OkHttp3Util.postAndGetBlock(getServerURL() + ReqCmd.getBlockByHash.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		return params.getDefaultSerializer().makeBlock(data);
	}

	public Block retryBlock(String hashHex) throws BlockStoreException, JsonProcessingException, IOException {
		return retryBlocks(getBlock(hashHex));
	}

	/*
	 * if a block is failed due to rating without conflict, it can be retried by
	 * setting new BlockPrototype.
	 */
	public Block retryBlocks(Block oldBlock) throws BlockStoreException, JsonProcessingException, IOException {

		Block block = getTip();
		block.setBlockType(oldBlock.getBlockType());
		for (Transaction transaction : oldBlock.getTransactions()) {
			block.addTransaction(transaction);

		}
		if (block.getTransactions().size() == 0) {
			return null;
		}
		return solveAndPost(block);
	}

	public Block rePayBlock(KeyParameter aesKey, String hashHex)
			throws BlockStoreException, JsonProcessingException, IOException {
		return retryBlocks(getBlock(hashHex));
	}

	public BigDecimal getLastPrice(String tokenid, String basetoken)
			throws JsonProcessingException, IOException, NoDataException {
		List<String> tokenids = new ArrayList<String>();
		tokenids.add(tokenid);
		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenids", tokenids);
		requestParam.put("count", 1);
		requestParam.put("basetoken", basetoken);
		byte[] response0 = OkHttp3Util.post(getServerURL() + ReqCmd.getOrdersTicker.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());
		OrderTickerResponse orderTickerResponse = Json.jsonmapper().readValue(response0, OrderTickerResponse.class);
		if (orderTickerResponse != null && !orderTickerResponse.getTickers().isEmpty()) {
			MatchLastdayResult matchResult = orderTickerResponse.getTickers().get(0);
			Token base = orderTickerResponse.getTokennames().get(matchResult.getBasetokenid());
			Integer priceshift = params.getOrderPriceShift(matchResult.getBasetokenid());
			// price is in orderbasetoken
			String price = MonetaryFormat.FIAT.noCode().format(matchResult.getPrice(), base.getDecimals() + priceshift);
			return new BigDecimal(price);
		}
		throw new NoDataException("tokenid=" + tokenid + " basetoken=" + basetoken);
	}

	public void changePassword(String password, String oldPassword) {

		Protos.ScryptParameters SCRYPT_PARAMETERS = Protos.ScryptParameters.newBuilder().setP(6).setR(8).setN(32768)
				.setSalt(ByteString.copyFrom(KeyCrypterScrypt.randomSalt())).build();
		KeyCrypterScrypt scrypt = new KeyCrypterScrypt(SCRYPT_PARAMETERS);
		KeyParameter aesKey = scrypt.deriveKey(password);
		if (isEncrypted()) {
			decrypt(oldPassword);
		}
		encrypt(scrypt, aesKey);
	}

	// use the fixed server
	public void setServerURL(String contextRoot) {
		serverPool = new ServerPool(params, new String[] { contextRoot });
	}

	public Boolean getFee() {
		return fee;
	}

	public void setFee(Boolean fee) {
		this.fee = fee;
	}

}
