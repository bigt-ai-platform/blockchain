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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.params.KeyParameter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import net.bigtangle.core.ECKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.crypto.DeterministicKey;
import net.bigtangle.crypto.EncryptionType;
import net.bigtangle.crypto.KeyCrypter;
import net.bigtangle.crypto.ScryptParameters;
import net.bigtangle.crypto.KeyCrypterException;
import net.bigtangle.crypto.KeyCrypterScrypt;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.pool.server.ServerPool;
import net.bigtangle.script.Script;
import net.bigtangle.signers.MissingSigResolutionSigner;
import net.bigtangle.signers.TransactionSigner;
import net.bigtangle.utils.BaseTaggableObject;
import net.bigtangle.utils.Threading;

import net.jcip.annotations.GuardedBy;

/**
 * <p>
 * A WalletBase provide keys and common service.
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
	protected KeyChainGroup keyChainGroup;

	protected NetworkParameters params;
 
 

	// The wallet version. This is an int that can be used to track breaking
	// changes in the wallet format.
	// You can also use it to detect wallets that come from the future (ie they
	// contain features you
	// do not know how to deal with).
	protected int version;

	// Objects that perform transaction signing. Applied subsequently one after
	// another
	@GuardedBy("lock")
	protected List<TransactionSigner> signers;

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
	 * Imports the given keys to the wallet. has been called, triggers an auto save
	 * bypassing the normal coalescing delay and event handlers. Returns the number
	 * of keys added, after duplicates are ignored. The onKeyAdded event will be
	 * called for each key in the list that was not already present.
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
	 * Convenience wrapper around
	 * {@link WalletBase#encrypt(net.bigtangle.crypto.KeyCrypter, org.bouncycastle.crypto.params.KeyParameter)}
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
	 * Get the type of encryption used for this wallet. (This is a convenience
	 * method - the encryption type is actually stored in the keyCrypter).
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

	// endregion

	// region Serialization support

	/** Internal use only. */
	protected List<byte[]> serializeKeyChainGroupToProtobuf() {
		keyChainGroupLock.lock();
		try {
			return keyChainGroup.serializeToProtobuf();
		} finally {
			keyChainGroupLock.unlock();
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
		 * If signature is missing, will be thrown for P2SH and
		 * {@link ECKey.MissingPrivateKeyException} for other tx types.
		 */
		THROW
	}

	/******************************************************************************************************************/

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

	// endregion

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

 
 

	public void changePassword(String password, String oldPassword) {

		ScryptParameters SCRYPT_PARAMETERS = new ScryptParameters(KeyCrypterScrypt.randomSalt(), 32768, 8, 6);
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
		List<ECKey> walletKeys = new ArrayList<>();
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
		return walletKeys(null);
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
