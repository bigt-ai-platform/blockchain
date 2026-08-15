/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2013 Google Inc.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.Nullable;

import org.bouncycastle.crypto.params.KeyParameter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import net.bigtangle.core.Key;
import net.bigtangle.core.PQKey;
import net.bigtangle.crypto.EncryptableItem;
import net.bigtangle.crypto.EncryptedData;
import net.bigtangle.crypto.KeyCrypter;
import net.bigtangle.crypto.KeyCrypterException;
import net.bigtangle.crypto.KeyCrypterScrypt;
import net.bigtangle.utils.Threading;

/**
 * A {@link KeyChain} that implements the simplest model possible: it can have keys imported into it, and just acts as
 * a dumb bag of keys. It will, left to its own devices, always return the same key for usage by the wallet, although
 * it will automatically add one to itself if it's empty or if encryption is requested.
 */
public class BasicKeyChain implements EncryptableKeyChain {
    private final ReentrantLock lock = Threading.lock("BasicKeyChain");

    // Maps used to let us quickly look up a key given data we find in transcations or the block chain.
    private final LinkedHashMap<ByteArrayKey, Key> hashToKeys;
    private final LinkedHashMap<ByteArrayKey, Key> pubkeyToKeys;
    @Nullable private final KeyCrypter keyCrypter;
    private boolean isWatching;

    
    public BasicKeyChain() {
        this(null);
    }

    public BasicKeyChain(@Nullable KeyCrypter crypter) {
        this.keyCrypter = crypter;
        hashToKeys = new LinkedHashMap<ByteArrayKey, Key>();
        pubkeyToKeys = new LinkedHashMap<ByteArrayKey, Key>();
   
    }

    /** Returns the {@link KeyCrypter} in use or null if the key chain is not encrypted. */
    @Override
    @Nullable
    public KeyCrypter getKeyCrypter() {
        lock.lock();
        try {
            return keyCrypter;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Key getKey(@Nullable KeyPurpose ignored) {
        lock.lock();
        try {
            if (hashToKeys.isEmpty()) {
                checkState(keyCrypter == null);   // We will refuse to encrypt an empty key chain.
                final Key key = PQKey.createNew();
         
            }
            return hashToKeys.values().iterator().next();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Key> getKeys(@Nullable KeyPurpose purpose, int numberOfKeys) {
        checkArgument(numberOfKeys > 0);
        lock.lock();
        try {
            if (hashToKeys.size() < numberOfKeys) {
                checkState(keyCrypter == null);

                List<Key> keys = new ArrayList<Key>();
                for (int i = 0; i < numberOfKeys - hashToKeys.size(); i++) {
                    keys.add(PQKey.createNew());
                }

                ImmutableList<Key> immutableKeys = ImmutableList.copyOf(keys);
                importKeysLocked(immutableKeys);
        
            }

            List<Key> keysToReturn = new ArrayList<Key>();
            int count = 0;
            while (hashToKeys.values().iterator().hasNext() && numberOfKeys != count) {
                keysToReturn.add(hashToKeys.values().iterator().next());
                count++;
            }
            return keysToReturn;
        } finally {
            lock.unlock();
        }
    }

    /** Returns a copy of the list of keys that this chain is managing. */
    public List<Key> getKeys() {
        lock.lock();
        try {
            return new ArrayList<Key>(hashToKeys.values());
        } finally {
            lock.unlock();
        }
    }

    public int importKeys(Key... keys) {
        return importKeys(Arrays.asList(keys));
    }

    public int importKeys(List<? extends Key> keys) {
        lock.lock();
        try {
            // Check that if we're encrypted, the keys are all encrypted, and if we're not, that none are.
            // We are NOT checking that the actual password matches here because we don't have access to the password at
            // this point: if you screw up and import keys with mismatched passwords, you lose! So make sure the
            // password is checked first.
            for (Key key : keys) {
                checkKeyEncryptionStateMatches(key);
            }
            List<Key> actuallyAdded = new ArrayList<Key>(keys.size());
            for (final Key key : keys) {
                if (hasKey(key)) continue;
                actuallyAdded.add(key);
                importKeyLocked(key);
            }
  
            return actuallyAdded.size();
        } finally {
            lock.unlock();
        }
    }

    private void checkKeyEncryptionStateMatches(Key key) {
        if (keyCrypter == null && key.isEncrypted())
            throw new KeyCrypterException("Key is encrypted but chain is not");
        else if (keyCrypter != null && !key.isEncrypted())
            throw new KeyCrypterException("Key is not encrypted but chain is");
        else if (keyCrypter != null && key.getKeyCrypter() != null && !key.getKeyCrypter().equals(keyCrypter))
            throw new KeyCrypterException("Key encrypted under different parameters to chain");
    }

    private void importKeyLocked(Key key) {
        if (hashToKeys.isEmpty()) {
            isWatching = key.isWatching();
        } else {
            if (key.isWatching() && !isWatching)
                throw new IllegalArgumentException("Key is watching but chain is not");
            if (!key.isWatching() && isWatching)
                throw new IllegalArgumentException("Key is not watching but chain is");
        }
        Key previousKey = pubkeyToKeys.put(new ByteArrayKey(key.getPubKey()), key);
        hashToKeys.put(new ByteArrayKey(key.getPubKeyHash()), key);
        checkState(previousKey == null);
    }

    private void importKeysLocked(List<Key> keys) {
        for (Key key : keys) {
            importKeyLocked(key);
        }
    }

    /**
     * Imports a key to the key chain. If key is present in the key chain, ignore it.
     */
    public void importKey(Key key) {
        lock.lock();
        try {
            checkKeyEncryptionStateMatches(key);
            if (hasKey(key)) return;
            importKeyLocked(key);
        
        } finally {
            lock.unlock();
        }
    }

    public Key findKeyFromPubHash(byte[] pubkeyHash) {
        lock.lock();
        try {
            return hashToKeys.get(new ByteArrayKey(pubkeyHash));
        } finally {
            lock.unlock();
        }
    }

    public Key findKeyFromPubKey(byte[] pubkey) {
        lock.lock();
        try {
            return pubkeyToKeys.get(new ByteArrayKey(pubkey));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean hasKey(Key key) {
        return findKeyFromPubKey(key.getPubKey()) != null;
    }

    @Override
    public int numKeys() {
        return pubkeyToKeys.size();
    }

    /** Whether this basic key chain is empty, full of regular (usable for signing) keys, or full of watching keys. */
    public enum State {
        EMPTY,
        WATCHING,
        REGULAR
    }

    /**
     * Returns whether this chain consists of pubkey only (watching) keys, regular keys (usable for signing), or
     * has no keys in it yet at all (thus we cannot tell).
     */
    public State isWatching() {
        lock.lock();
        try {
            if (hashToKeys.isEmpty())
                return State.EMPTY;
            return isWatching ? State.WATCHING : State.REGULAR;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes the given key from the keychain. Be very careful with this - losing a private key <b>destroys the
     * money associated with it</b>.
     * @return Whether the key was removed or not.
     */
    public boolean removeKey(Key key) {
        lock.lock();
        try {
            boolean a = hashToKeys.remove(new ByteArrayKey(key.getPubKeyHash())) != null;
            boolean b = pubkeyToKeys.remove(new ByteArrayKey(key.getPubKey())) != null;
            checkState(a == b);   // Should be in both maps or neither.
            return a;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long getEarliestKeyCreationTime() {
        lock.lock();
        try {
            long time = Long.MAX_VALUE;
            for (Key key : hashToKeys.values())
                time = Math.min(key.getCreationTimeSeconds(), time);
            return time;
        } finally {
            lock.unlock();
        }
    }
 
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Serialization support
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public List<byte[]> serializeToProtobuf() {
        return new ArrayList<>();
    }

    public static BasicKeyChain fromProtobufUnencrypted(List<byte[]> keys) throws UnreadableWalletException {
        return new BasicKeyChain();
    }

    public static BasicKeyChain fromProtobufEncrypted(List<byte[]> keys, KeyCrypter crypter) throws UnreadableWalletException {
        return new BasicKeyChain(checkNotNull(crypter));
    }

 
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Encryption support
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Convenience wrapper around {@link #toEncrypted(net.bigtangle.crypto.KeyCrypter,
     * org.bouncycastle.crypto.params.KeyParameter)} which uses the default Scrypt key derivation algorithm and
     * parameters, derives a key from the given password and returns the created key.
     */
    @Override
    public BasicKeyChain toEncrypted(CharSequence password) {
        checkNotNull(password);
        checkArgument(password.length() > 0);
        KeyCrypter scrypt = new KeyCrypterScrypt();
        KeyParameter derivedKey = scrypt.deriveKey(password);
        return toEncrypted(scrypt, derivedKey);
    }

    /**
     * Encrypt the wallet using the KeyCrypter and the AES key. A good default KeyCrypter to use is
     * {@link net.bigtangle.crypto.KeyCrypterScrypt}.
     *
     * @param keyCrypter The KeyCrypter that specifies how to encrypt/ decrypt a key
     * @param aesKey AES key to use (normally created using KeyCrypter#deriveKey and cached as it is time consuming
     *               to create from a password)
     * @throws KeyCrypterException Thrown if the wallet encryption fails. If so, the wallet state is unchanged.
     */
    @Override
    public BasicKeyChain toEncrypted(KeyCrypter keyCrypter, KeyParameter aesKey) {
        lock.lock();
        try {
            checkNotNull(keyCrypter);
            checkState(this.keyCrypter == null, "Key chain is already encrypted");
            BasicKeyChain encrypted = new BasicKeyChain(keyCrypter);
            for (Key key : hashToKeys.values()) {
                Key encryptedKey = key.encrypt(keyCrypter, aesKey);
                // Check that the encrypted key can be successfully decrypted.
                // This is done as it is a critical failure if the private key cannot be decrypted successfully
                // (all bitcoin controlled by that private key is lost forever).
                // For a correctly constructed keyCrypter the encryption should always be reversible so it is just
                // being as cautious as possible.
                try {
                    encryptedKey.decrypt(keyCrypter, aesKey);
                } catch (KeyCrypterException e) {
                    throw new KeyCrypterException("The key " + key.toString() + " cannot be successfully decrypted after encryption so aborting wallet encryption.", e);
                }
                encrypted.importKeyLocked(encryptedKey);
            }
            return encrypted;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public BasicKeyChain toDecrypted(CharSequence password) {
        checkNotNull(keyCrypter, "Wallet is already decrypted");
        KeyParameter aesKey = keyCrypter.deriveKey(password);
        return toDecrypted(aesKey);
    }

    @Override
    public BasicKeyChain toDecrypted(KeyParameter aesKey) {
        lock.lock();
        try {
            checkState(keyCrypter != null, "Wallet is already decrypted");
            // Do an up-front check.
            if (numKeys() > 0 && !checkAESKey(aesKey))
                throw new KeyCrypterException("Password/key was incorrect.");
            BasicKeyChain decrypted = new BasicKeyChain();
            for (Key key : hashToKeys.values()) {
                decrypted.importKeyLocked(key.decrypt(aesKey));
            }
            return decrypted;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns whether the given password is correct for this key chain.
     * @throws IllegalStateException if the chain is not encrypted at all.
     */
    @Override
    public boolean checkPassword(CharSequence password) {
        checkNotNull(password);
        checkState(keyCrypter != null, "Key chain not encrypted");
        return checkAESKey(keyCrypter.deriveKey(password));
    }

    /**
     * Check whether the AES key can decrypt the first encrypted key in the wallet.
     *
     * @return true if AES key supplied can decrypt the first encrypted private key in the wallet, false otherwise.
     */
    @Override
    public boolean checkAESKey(KeyParameter aesKey) {
        lock.lock();
        try {
            // If no keys then cannot decrypt.
            if (hashToKeys.isEmpty()) return false;
            checkState(keyCrypter != null, "Key chain is not encrypted");

            // Find the first encrypted key in the wallet.
            Key first = null;
            for (Key key : hashToKeys.values()) {
                if (key.isEncrypted()) {
                    first = key;
                    break;
                }
            }
            checkState(first != null, "No encrypted keys in the wallet");

            try {
                Key rebornKey = first.decrypt(aesKey);
                return Arrays.equals(first.getPubKey(), rebornKey.getPubKey());
            } catch (KeyCrypterException e) {
                // The AES key supplied is incorrect.
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

  

    @Override
    public int numBloomFilterEntries() {
        return numKeys() * 2;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Key rotation support
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /** Returns the first Key created after the given UNIX time, or null if there is none. */
    @Nullable
    public Key findOldestKeyAfter(long timeSecs) {
        lock.lock();
        try {
            Key oldest = null;
            for (Key key : hashToKeys.values()) {
                final long keyTime = key.getCreationTimeSeconds();
                if (keyTime > timeSecs) {
                    if (oldest == null || oldest.getCreationTimeSeconds() > keyTime)
                        oldest = key;
                }
            }
            return oldest;
        } finally {
            lock.unlock();
        }
    }

    /** Returns a list of all ECKeys created after the given UNIX time. */
    public List<Key> findKeysBefore(long timeSecs) {
        lock.lock();
        try {
            List<Key> results = Lists.newLinkedList();
            for (Key key : hashToKeys.values()) {
                final long keyTime = key.getCreationTimeSeconds();
                if (keyTime < timeSecs) {
                    results.add(key);
                }
            }
            return results;
        } finally {
            lock.unlock();
        }
    }
}
