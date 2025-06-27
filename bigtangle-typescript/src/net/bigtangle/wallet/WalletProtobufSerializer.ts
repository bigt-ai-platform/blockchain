import { Wallet } from './Wallet';
import { KeyChainGroup } from './KeyChainGroup';
import { KeyCrypterScrypt } from '../crypto/KeyCrypterScrypt';
import { NetworkParameters } from '../core/NetworkParameters';
import { Sha256Hash } from '../core/Sha256Hash';
import { UnreadableWalletException } from './UnreadableWalletException';
import { WalletExtension } from './WalletExtension';
import { DefaultKeyChainFactory } from './DefaultKeyChainFactory';
import { Buffer } from 'buffer';
import { KeyChainFactory } from './KeyChainFactory';

export class WalletProtobufSerializer {
    public static readonly CURRENT_WALLET_VERSION = 1; // Assuming a simple version for now
    private static readonly WALLET_SIZE_LIMIT = 1024 * 1024; // 1 MB

    private readonly factory: WalletProtobufSerializer.WalletFactory;
    private keyChainFactory: KeyChainFactory;

    constructor(factory?: WalletProtobufSerializer.WalletFactory) {
        this.factory = factory || new WalletProtobufSerializer.WalletFactoryImpl();
        this.keyChainFactory = new DefaultKeyChainFactory();
    }

    public setKeyChainFactory(keyChainFactory: KeyChainFactory): void {
        this.keyChainFactory = keyChainFactory;
    }

    public writeWallet(wallet: Wallet, output: any): void {
        const walletProto = this.walletToProto(wallet);
        // Assuming walletProto has a method to write to a stream/output
        // This part would depend on the actual Protobuf.js implementation
        // For now, we'll just convert to a buffer and write
        output.write(Buffer.from(JSON.stringify(walletProto))); // Simplified for now
    }

    public walletToText(wallet: Wallet): string {
        const walletProto = this.walletToProto(wallet);
        return JSON.stringify(walletProto, null, 2); // Pretty print JSON
    }

    // --- Fix walletToProto and related Protos usage ---
    // Use a plain object for walletProto, since Protos.Wallet.Wallet and Builder do not exist
    public walletToProto(wallet: Wallet): any {
        const walletProto: any = {};
        walletProto.networkIdentifier = wallet.getNetworkParameters().getId();
        // Use a public method to get keychain group serialization, or expose it if needed
        walletProto.keys = wallet['serializeKeyChainGroupToProtobuf'] ? wallet['serializeKeyChainGroupToProtobuf']() : [];

        const keyCrypter = wallet.getKeyCrypter();
        if (keyCrypter === null) {
            walletProto.encryptionType = 'UNENCRYPTED';
        } else {
            walletProto.encryptionType = keyCrypter.getUnderstoodEncryptionType();
            if (keyCrypter instanceof KeyCrypterScrypt) {
                walletProto.encryptionParameters = keyCrypter.getScryptParameters();
            } else {
                throw new Error(`The wallet has encryption of type '${keyCrypter.getUnderstoodEncryptionType()}' but this WalletProtobufSerializer does not know how to persist this.`);
            }
        }
        // Tags and other fields can be added as needed
        return walletProto;
    }

    public static hashToByteString(hash: Sha256Hash): Uint8Array {
        // Use toBuffer if available, else fallback to value property
        if (typeof hash.toBuffer === 'function') return hash.toBuffer();
        // @ts-ignore
        if (hash.value) return hash.value;
        throw new Error('Sha256Hash does not support toBuffer or value');
    }

    public static byteStringToHash(bs: Uint8Array): Sha256Hash {
        // Convert Uint8Array to Buffer if needed
        const buf = Buffer.from(bs);
        return Sha256Hash.wrap(buf);
    }

    public readWallet(input: any, forceReset: boolean, extensions: WalletExtension[]): Wallet {
        try {
            const walletProto = this.parseToProto(input);
            const paramsID = walletProto.getNetworkIdentifier();
            const params = NetworkParameters.fromID(paramsID);
            if (params === null) {
                throw new UnreadableWalletException(`Unknown network parameters ID ${paramsID}`);
            }
            return this.readWalletInternal(params, extensions, walletProto, forceReset);
        } catch (e: any) {
            throw new UnreadableWalletException("Could not parse input stream to protobuf", e);
        }
    }

    public readWalletInternal(params: NetworkParameters, extensions: WalletExtension[], walletProto: any, forceReset: boolean): Wallet {
        if (walletProto.version && walletProto.version > WalletProtobufSerializer.CURRENT_WALLET_VERSION) {
            throw new UnreadableWalletException.FutureVersion();
        }
        let keyChainGroup: KeyChainGroup;
        if (walletProto.encryptionParameters) {
            const keyCrypter = new KeyCrypterScrypt(walletProto.encryptionParameters);
            keyChainGroup = KeyChainGroup.fromProtobufEncrypted(params, walletProto.keys, keyCrypter, this.keyChainFactory);
        } else {
            keyChainGroup = KeyChainGroup.fromProtobufUnencrypted(params, walletProto.keys, this.keyChainFactory);
        }
        const wallet = this.factory.create(params, keyChainGroup);
        if (walletProto.version) {
            wallet.setVersion(walletProto.version);
        }
        return wallet;
    }

    public parseToProto(input: any): any {
        // For now, assuming input is a stringified JSON
        const jsonString = input.read(); // Assuming read method exists
        const parsed = JSON.parse(jsonString);
        return parsed;
    }

    public static isWallet(is: any): boolean {
        // For now, a simplified check
        const firstByte = is.read(); // Assuming read method exists
        return firstByte !== undefined; // Very basic check
    }
}

export namespace WalletProtobufSerializer {
    export interface WalletFactory {
        create(params: NetworkParameters, keyChainGroup: KeyChainGroup): Wallet;
    }

    export class WalletFactoryImpl implements WalletFactory {
        create(params: NetworkParameters, keyChainGroup: KeyChainGroup): Wallet {
            return new Wallet(params, keyChainGroup);
        }
    }
}
