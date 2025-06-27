import { ECKey } from '../core/ECKey';
import { RedeemData } from './RedeemData';

/**
 * A KeyBag is simply an object that can map public keys, their 160-bit hashes and script hashes to ECKey
 * and {@link RedeemData} objects.
 */
export interface KeyBag {
    /**
     * Locates a keypair from the keychain given the hash of the public key. This is needed when finding out which
     * key we need to use to redeem a transaction output.
     *
     * @return ECKey object or null if no such key was found.
     */
    findKeyFromPubHash(pubkeyHash: Uint8Array): ECKey | null;

    /**
     * Locates a keypair from the keychain given the raw public key bytes.
     *
     * @return ECKey or null if no such key was found.
     */
    findKeyFromPubKey(pubkey: Uint8Array): ECKey | null;

    /**
     * Locates a redeem data (redeem script and keys) from the keychain given the hash of the script.
     * This is needed when finding out which key and script we need to use to locally sign a P2SH transaction input.
     * It is assumed that wallet should not have more than one private key for a single P2SH tx for security reasons.
     *
     * Returns RedeemData object or null if no such data was found.
     */
    findRedeemDataFromScriptHash(scriptHash: Uint8Array): RedeemData | null;
}
