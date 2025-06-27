import { ECKey } from '../core/ECKey';
import { KeyParameter } from '../crypto/KeyCrypter';
import { KeyBag } from './KeyBag';
import { RedeemData } from './RedeemData';

/**
 * A DecryptingKeyBag filters a pre-existing key bag, decrypting keys as they are requested using the provided
 * AES key. If the keys are encrypted and no AES key provided, {@link net.bigtangle.core.ECKey.KeyIsEncryptedException}
 * will be thrown.
 */
export class DecryptingKeyBag implements KeyBag {
    protected readonly target: KeyBag;
    protected readonly aesKey: KeyParameter | null;

    constructor(target: KeyBag, aesKey: KeyParameter | null) {
        this.target = target;
        this.aesKey = aesKey;
    }

    public maybeDecrypt(key: ECKey | null): ECKey | null {
        if (key === null) {
            return null;
        } else if (key.isEncrypted()) {
            if (this.aesKey === null) {
                throw new Error("KeyIsEncryptedException: Key is encrypted but no AES key provided.");
            }
            // Assuming ECKey.decrypt takes KeyParameter and returns ECKey
            return key.decrypt(key.getKeyCrypter()!, this.aesKey); // Pass keyCrypter from the key itself
        } else {
            return key;
        }
    }

    private maybeDecryptRedeemData(redeemData: RedeemData | null): RedeemData | null {
        if (redeemData === null) return null;
        const decryptedKeys: ECKey[] = [];
        for (const key of redeemData.keys) {
            const decryptedKey = this.maybeDecrypt(key);
            if (decryptedKey) {
                decryptedKeys.push(decryptedKey);
            }
        }
        // Assuming RedeemData.of can take an array of ECKey and a Script
        return RedeemData.of(decryptedKeys, redeemData.redeemScript);
    }

    public findKeyFromPubHash(pubkeyHash: Uint8Array): ECKey | null {
        return this.maybeDecrypt(this.target.findKeyFromPubHash(pubkeyHash));
    }

    public findKeyFromPubKey(pubkey: Uint8Array): ECKey | null {
        return this.maybeDecrypt(this.target.findKeyFromPubKey(pubkey));
    }

    public findRedeemDataFromScriptHash(scriptHash: Uint8Array): RedeemData | null {
        return this.maybeDecryptRedeemData(this.target.findRedeemDataFromScriptHash(scriptHash));
    }
}
