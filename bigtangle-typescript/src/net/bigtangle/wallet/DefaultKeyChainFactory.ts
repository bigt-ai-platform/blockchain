import { KeyChainFactory } from './KeyChainFactory';
import { Protos } from './Protos';
import { DeterministicSeed } from './DeterministicSeed';
import { KeyCrypter } from '../crypto/KeyCrypter';
import { DeterministicKeyChain } from './DeterministicKeyChain';
import { MarriedKeyChain } from './MarriedKeyChain';
import { DeterministicKey } from '../crypto/DeterministicKey';
import { UnreadableWalletException } from './UnreadableWalletException';
import { HDUtils } from '../crypto/HDUtils';

/**
 * Default factory for creating keychains while de-serializing.
 */
export class DefaultKeyChainFactory implements KeyChainFactory {
    public makeKeyChain(key: Protos.Key.Key, firstSubKey: Protos.Key.Key, seed: DeterministicSeed, crypter: KeyCrypter, isMarried: boolean): DeterministicKeyChain {
        let chain: DeterministicKeyChain;
        if (isMarried) {
            chain = new MarriedKeyChain(seed, crypter);
        } else {
            chain = new DeterministicKeyChain(seed, crypter);
        }
        return chain;
    }

    public makeWatchingKeyChain(key: Protos.Key.Key, firstSubKey: Protos.Key.Key, accountKey: DeterministicKey,
                                                      isFollowingKey: boolean, isMarried: boolean): DeterministicKeyChain {
        if (!accountKey.getPath().equals(DeterministicKeyChain.ACCOUNT_ZERO_PATH)) {
            throw new UnreadableWalletException(`Expecting account key but found key with path: ${HDUtils.formatPath(accountKey.getPath())}`);
        }
        let chain: DeterministicKeyChain;
        if (isMarried) {
            chain = new MarriedKeyChain(accountKey);
        } else {
            chain = new DeterministicKeyChain(accountKey, isFollowingKey);
        }
        return chain;
    }
}
