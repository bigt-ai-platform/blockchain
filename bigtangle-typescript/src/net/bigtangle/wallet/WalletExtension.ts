import { Wallet } from './Wallet';

/**
 * <p>An object implementing this interface can be added to a {@link Wallet} and provide arbitrary byte arrays that will
 * be serialized alongside the wallet. Extensions can be mandatory, in which case applications that don't know how to
 * read the given data will refuse to load the wallet at all. Extensions identify themselves with a string ID that
 * should use a Java-style reverse DNS identifier to avoid being mixed up with other kinds of extension. To use an
 * extension, add an object that implements this interface to the wallet using {@link Wallet#addExtension(WalletExtension)}
 * before you load it (to read existing data) and ensure it's present when the wallet is save (to write the data).</p>
 *
 * <p>Note that extensions are singletons - you cannot add two objects that provide the same ID to the same wallet.</p>
 */
export interface WalletExtension {
    /** Returns a Java package/class style name used to disambiguate this extension from others. */
    getWalletExtensionID(): string;

    /**
     * If this returns true, the mandatory flag is set when the wallet is serialized and attempts to load it without
     * the extension being in the wallet will throw an exception. This method should not change its result during
     * the objects lifetime.
     */
    isWalletExtensionMandatory(): boolean;

    /** Returns bytes that will be saved in the wallet. */
    serializeWalletExtension(): Uint8Array;
    /** Loads the contents of this object from the wallet. */
    deserializeWalletExtension(containingWallet: Wallet, data: Uint8Array): void;
}
