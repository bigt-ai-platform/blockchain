import { Protos } from './Protos';
import { DeterministicSeed } from './DeterministicSeed';
import { KeyCrypter } from '../../crypto/KeyCrypter';
import { DeterministicKeyChain } from './DeterministicKeyChain';
import { DeterministicKey } from '../../crypto/DeterministicKey';
import { UnreadableWalletException } from './UnreadableWalletException';

/**
 * Factory interface for creation keychains while de-serializing a wallet.
 */
export interface KeyChainFactory {
    /**
     * Make a keychain (but not a watching one).
     *
     * @param key the protobuf for the root key
     * @param firstSubKey the protobuf for the first child key (normally the parent of the external subchain)
     * @param seed the seed
     * @param crypter the encrypted/decrypter
     * @param isMarried whether the keychain is leading in a marriage
     */
    makeKeyChain(key: Protos.Key.Key, firstSubKey: Protos.Key.Key, seed: DeterministicSeed, crypter: KeyCrypter, isMarried: boolean): DeterministicKeyChain;

    /**
     * Make a watching keychain.
     *
     * <p>isMarried and isFollowingKey must not be true at the same time.
     *
     * @param key the protobuf for the account key
     * @param firstSubKey the protobuf for the first child key (normally the parent of the external subchain)
     * @param accountKey the account extended public key
     * @param isFollowingKey whether the keychain is following in a marriage
     * @param isMarried whether the keychain is leading in a marriage
     */
    makeWatchingKeyChain(key: Protos.Key.Key, firstSubKey: Protos.Key.Key, accountKey: DeterministicKey, isFollowingKey: boolean, isMarried: boolean): DeterministicKeyChain;
}
