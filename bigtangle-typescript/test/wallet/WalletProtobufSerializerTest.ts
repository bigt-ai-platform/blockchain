
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
        myWatchedKey = new ECKey();
        myWallet = Wallet.fromKeys(PARAMS, myWatchedKey);
        myKey = new ECKey();
        myKey.setCreationTimeSeconds(123456789);
        myWallet.importKey(myKey);
        myAddress = myKey.toAddress(PARAMS);
        myWallet = Wallet.fromKeys(PARAMS, myKey);
        myWallet.importKey(myKey);
    });

    function roundTrip(wallet: Wallet): Wallet {
        const output = new WalletProtobufSerializer().writeWallet(wallet);
        return new WalletProtobufSerializer().readWallet(output);
    }

    test('empty', () => {
        const wallet1 = roundTrip(myWallet);

        expect(
            Buffer.compare(
                myKey.getPubKey(),
                wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPubKey(),
            ),
        ).toBe(0);
        expect(
            Buffer.compare(
                myKey.getPrivKeyBytes(),
                wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPrivKeyBytes(),
            ),
        ).toBe(0);
        expect(
            wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getCreationTimeSeconds(),
        ).toBe(myKey.getCreationTimeSeconds());
    });

    test('testKeys', () => {
        for (let i = 0; i < 20; i++) {
            myKey = new ECKey();
            myAddress = myKey.toAddress(PARAMS);
            myWallet = Wallet.fromKeys(PARAMS, myKey);

            const wallet1 = roundTrip(myWallet);
            expect(
                Buffer.compare(
                    myKey.getPubKey(),
                    wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPubKey(),
                ),
            ).toBe(0);
            expect(
                Buffer.compare(
                    myKey.getPrivKeyBytes(),
                    wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPrivKeyBytes(),
                ),
            ).toBe(0);
        }
    });

    test('testRoundTripNormalWallet', () => {
        const wallet1 = roundTrip(myWallet);

        expect(
            Buffer.compare(
                myKey.getPubKey(),
                wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPubKey(),
            ),
        ).toBe(0);
        expect(
            Buffer.compare(
                myKey.getPrivKeyBytes(),
                wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPrivKeyBytes(),
            ),
        ).toBe(0);
        expect(
            wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getCreationTimeSeconds(),
        ).toBe(myKey.getCreationTimeSeconds());
    });

    test('tags', () => {
        myWallet.setTag('foo', Buffer.from('bar', 'utf-8'));
        expect(myWallet.getTag('foo').toString('utf-8')).toBe('bar');
        myWallet = roundTrip(myWallet);
        expect(myWallet.getTag('foo').toString('utf-8')).toBe('bar');
    });

    test('versions', () => {
        expect(() => {
            const proto = new WalletProtobufSerializer().walletToProto(myWallet);
            proto.setVersion(2);
            new WalletProtobufSerializer().readWallet(
                new WalletProtobufSerializer().protoToWallet(proto),
            );
        }).toThrow(UnreadableWalletException.FutureVersion);
    });
});
