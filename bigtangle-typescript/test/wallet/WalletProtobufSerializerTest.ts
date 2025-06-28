import { Buffer } from 'buffer';
import { Wallet } from '../../src/net/bigtangle/wallet/Wallet';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { Address } from '../../src/net/bigtangle/core/Address';
import { WalletProtobufSerializer } from '../../src/net/bigtangle/wallet/WalletProtobufSerializer';
import { UnreadableWalletException } from '../../src/net/bigtangle/wallet/UnreadableWalletException';

describe('WalletProtobufSerializerTest', () => {
    const PARAMS = MainNetParams.get();
    let myKey: ECKey;
    let myWatchedKey: ECKey;
    let myAddress: Address;
    let myWallet: Wallet;

    beforeEach(() => {
        // Use ECKey.fromPrivate with a dummy BigInteger (e.g., new BigInteger('1'))
        const BigInteger = require('../../src/net/bigtangle/core/BigInteger').BigInteger;
        const priv1 = new BigInteger('1');
        const priv2 = new BigInteger('2');
        myWatchedKey = ECKey.fromPrivate(priv1);
        myKey = ECKey.fromPrivate(priv2);
        myKey.setCreationTimeSeconds(123456789);
        myWallet = Wallet.fromKeys(PARAMS, [myWatchedKey, myKey]);
        myWallet.importKey(myKey);
    });

    function roundTrip(wallet: Wallet): Wallet {
        // Mock output/input for serializer
        let data = '';
        const output = { write: (buf: Buffer) => { data = buf.toString(); } };
        new WalletProtobufSerializer().writeWallet(wallet, output);
        const input = { read: () => data };
        return new WalletProtobufSerializer().readWallet(input, false, []);
    }

    test('empty', () => {
        const wallet1 = roundTrip(myWallet);
        const key1 = wallet1.findKeyFromPubHash(myKey.getPubKeyHash());
        expect(key1).not.toBeNull();
        expect(Buffer.compare(myKey.getPubKey(), key1!.getPubKey())).toBe(0);
        expect(Buffer.compare(myKey.getPrivKeyBytes(), key1!.getPrivKeyBytes())).toBe(0);
        expect(key1!.getCreationTimeSeconds()).toBe(myKey.getCreationTimeSeconds());
    });

    test('testKeys', () => {
        const BigInteger = require('../../src/net/bigtangle/core/BigInteger').BigInteger;
        for (let i = 0; i < 20; i++) {
            myKey = ECKey.fromPrivate(new BigInteger((i + 10).toString()));
            myWallet = Wallet.fromKeys(PARAMS, [myKey]);
            const wallet1 = roundTrip(myWallet);
            const key1 = wallet1.findKeyFromPubHash(myKey.getPubKeyHash());
            expect(key1).not.toBeNull();
            expect(Buffer.compare(myKey.getPubKey(), key1!.getPubKey())).toBe(0);
            expect(Buffer.compare(myKey.getPrivKeyBytes(), key1!.getPrivKeyBytes())).toBe(0);
        }
    });

    test('testRoundTripNormalWallet', () => {
        const wallet1 = roundTrip(myWallet);
        const key1 = wallet1.findKeyFromPubHash(myKey.getPubKeyHash());
        expect(key1).not.toBeNull();
        expect(Buffer.compare(myKey.getPubKey(), key1!.getPubKey())).toBe(0);
        expect(Buffer.compare(myKey.getPrivKeyBytes(), key1!.getPrivKeyBytes())).toBe(0);
        expect(key1!.getCreationTimeSeconds()).toBe(myKey.getCreationTimeSeconds());
    });

    test('versions', () => {
        expect(() => {
            const proto = new WalletProtobufSerializer().walletToProto(myWallet);
            proto.version = 2; // setVersion does not exist, set property directly
            // Mock output/input for serializer
            let data = JSON.stringify(proto);
            const input = { read: () => data };
            new WalletProtobufSerializer().readWallet(input, false, []);
        }).toThrow(UnreadableWalletException.FutureVersion);
    });
});
