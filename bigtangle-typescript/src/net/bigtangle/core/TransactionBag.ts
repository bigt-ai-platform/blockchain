import { Script } from '../script/Script';

/**
 * This interface is used to abstract the {@link net.bigtangle.wallet.Wallet} and the {@link net.bigtangle.core.Transaction}
 */
export interface TransactionBag {
    /** Returns true if this wallet contains a public key which hashes to the given hash. */
    isPubKeyHashMine(pubkeyHash: Uint8Array): boolean;

    /** Returns true if this wallet is watching transactions for outputs with the script. */
    isWatchedScript(script: Script): boolean;

    /** Returns true if this wallet contains a keypair with the given public key. */
    isPubKeyMine(pubkey: Uint8Array): boolean;

    /** Returns true if this wallet knows the script corresponding to the given hash. */
    isPayToScriptHashMine(payToScriptHash: Uint8Array): boolean;
}