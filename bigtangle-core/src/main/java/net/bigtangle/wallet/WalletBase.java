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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;

import net.bigtangle.core.Address;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.Utils;
import net.bigtangle.core.VarInt;
import net.bigtangle.core.exception.InsufficientMoneyException;
import net.bigtangle.core.exception.ScriptException;
import net.bigtangle.crypto.ChildNumber;
import net.bigtangle.crypto.DeterministicKey;
import net.bigtangle.crypto.KeyCrypter;
import net.bigtangle.crypto.KeyCrypterException;
import net.bigtangle.crypto.KeyCrypterScrypt;
import net.bigtangle.pool.server.ServerPool;
import net.bigtangle.script.Script;
import net.bigtangle.signers.MissingSigResolutionSigner;
import net.bigtangle.signers.TransactionSigner;
import net.bigtangle.utils.BaseTaggableObject;
import net.bigtangle.utils.Threading;
import net.bigtangle.wallet.Protos.Wallet.EncryptionType;
import net.jcip.annotations.GuardedBy;

/**
 * <p>
 * A WalletBase provide keys and  common service.
 * </p>
 */

public abstract class WalletBase extends BaseTaggableObject implements KeyBag {

	private static final Logger log = LoggerFactory.getLogger(WalletBase.class);

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
	protected static final int SPENTPENDINGTIMEOUT = 120000;// 2 minutues

	// The key chain group is not thread safe, and generally the whole hierarchy
	// of objects should not be mutated
	// outside the wallet lock. So don't expose this object directly via any
	// accessors!
	@GuardedBy("keyChainGroupLock")
	protected  KeyChainGroup keyChainGroup;

	protected  NetworkParameters params;

	protected volatile WalletFiles vFileManager;
	// Object that is used to send transactions asynchronously when the wallet
	// requires it.

	// UNIX time in seconds. Money controlled by keys created before this time
	// will be automatically respent to a key
	// that was created after it. Useful when you believe some keys have been
	// compromised.
	protected volatile long vKeyRotationTimestamp;

	protected CoinSelector coinSelector = new DefaultCoinSelector();

	// The wallet version. This is an int that can be used to track breaking
	// changes in the wallet format.
	// You can also use it to detect wallets that come from the future (ie they
	// contain features you
	// do not know how to deal with).
	protected int version;

	// Objects that perform transaction signing. Applied subsequently one after
	// another
	@GuardedBy("lock")
	protected  List<TransactionSigner> signers;


 
	public NetworkParameters getNetworkParameters() {
		return params;
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
	 * {@link WalletBase#autosaveToFile(java.io.File, long, java.util.concurrent.TimeUnit, net.bigtangle.wallet.WalletFiles.Listener)}
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
	 * {@link WalletBase#encrypt(net.bigtangle.crypto.KeyCrypter, org.spongycastle.crypto.params.KeyParameter)}
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
	public abstract void saveToFileStream(OutputStream f) throws IOException  ;

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
	 * Set the version number of the wallet. See {@link WalletBase#getVersion()}.
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
		 * will
		 * be thrown for P2SH and {@link ECKey.MissingPrivateKeyException} for other tx
		 * types.
		 */
		THROW
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
            return recipientKey != null && (recipientKey.isEncrypted() || recipientKey.hasPrivKey());
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

	public abstract WalletFiles autosaveToFile(File f, long delayTime, TimeUnit timeUnit,
			@Nullable WalletFiles.Listener eventListener) ;

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
			CoinSelection selection = selector.select(valueNeeded, new LinkedList<>(candidates));
			// Can we afford this?
			if (selection.valueGathered.compareTo(valueNeeded) < 0) {
				valueMissing = valueNeeded.subtract(selection.valueGathered);
				break;
			}
			checkState(!selection.gathered.isEmpty() || !originalInputs.isEmpty());

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
                // must be category 1

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
	 
		public void signTransaction(Transaction tx, KeyParameter aesKey, MissingSigsMode missingSigsMode) {
			lock.lock();
			try {

				List<TransactionInput> inputs = tx.getInputs();
				List<TransactionOutput> outputs = tx.getOutputs();
				checkState(!inputs.isEmpty());
				checkState(!outputs.isEmpty());

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

	/*****************************************************************************************************************/

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
	 * {@link  (org.spongycastle.crypto.params.KeyParameter, boolean)}
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

			HashMap<String, Address> addressResult = new HashMap<>();

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

}
