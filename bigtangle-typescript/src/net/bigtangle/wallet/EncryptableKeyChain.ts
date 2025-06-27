import { KeyChain } from './KeyChain';
import { KeyCrypter, KeyCrypterException, KeyParameter } from '../crypto/KeyCrypter';
import { KeyCrypterScrypt } from '../crypto/KeyCrypterScrypt';

/**
 * An encryptable key chain is a key-chain that can be encrypted with a user-provided password or AES key.
 */
export interface EncryptableKeyChain extends KeyChain {
    /**
     * Takes the given password, which should be strong, derives a key from it and then invokes
     * {@link #toEncrypted(net.bigtangle.crypto.KeyCrypter, org.spongycastle.crypto.params.KeyParameter)} with
     * {@link net.bigtangle.crypto.KeyCrypterScrypt} as the crypter.
     *
     * @return The derived key, in case you wish to cache it for future use.
     */
    toEncrypted(password: string): EncryptableKeyChain;

    /**
     * Returns a new keychain holding identical/cloned keys to this chain, but encrypted under the given key.
     * Old keys and keychains remain valid and so you should ensure you don't accidentally hold references to them.
     */
    toEncrypted(keyCrypter: KeyCrypter, aesKey: KeyParameter): EncryptableKeyChain;

    /**
     * Decrypts the key chain with the given password. See {@link #toDecrypted(org.spongycastle.crypto.params.KeyParameter)}
     * for details.
     */
    toDecrypted(password: string): EncryptableKeyChain;

    /**
     * Decrypt the key chain with the given AES key and whatever {@link KeyCrypter} is already set. Note that if you
     * just want to spend money from an encrypted wallet, don't decrypt the whole thing first. Instead, set the
     *  field before asking the wallet to build the send.
     *
     * @param aesKey AES key to use (normally created using KeyCrypter#deriveKey and cached as it is time consuming to
     *               create from a password)
     * @throws KeyCrypterException Thrown if the wallet decryption fails. If so, the wallet state is unchanged.
     */
    toDecrypted(aesKey: KeyParameter): EncryptableKeyChain;

    checkPassword(password: string): boolean;
    checkAESKey(aesKey: KeyParameter): boolean;

    /** Returns the key crypter used by this key chain, or null if it's not encrypted. */
    getKeyCrypter(): KeyCrypter | null;
}
