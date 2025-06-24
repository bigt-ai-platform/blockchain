import { ECKey } from './ECKey';
import { TestParams } from './TestParams';
import { Wallet } from './wallet/Wallet';
import { Token } from './Token';
import { TokenType } from './TokenType';
import { MultiSignAddress } from './MultiSignAddress';
import { Sha256Hash } from './Sha256Hash';
import { Block } from './Block';
import { Utils } from './Utils';
import { KeyValue } from './KeyValue';
import { TokenKeyValues } from './TokenKeyValues';
import { BigInteger } from 'jsbn';
import { initCoin } from './Coin';

initCoin();

function toBigInteger(value: bigint): BigInteger {
    return new BigInteger(value.toString());
}

describe('RemoteTokenTests', () => {
    let wallet: Wallet;
    const testPub = "02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975";
    const testPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";
    const yuanTokenPub = "02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a";
    const yuanTokenPriv = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";
    let contextRoot: string;

    beforeEach(() => {
        contextRoot = "http://localhost:8088/";
        wallet = Wallet.fromKeys(TestParams.get(), ECKey.fromPrivate(Utils.HEX.decode(testPriv)), contextRoot);
    });

    it('test tokens', async () => {
        const domain = "";
        const fromPrivate = ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv));
        await testCreateMultiSigToken(fromPrivate, "人民币", 2, domain, "人民币 CNY", toBigInteger(1000000000n));
    });

    async function testCreateMultiSigToken(key: ECKey, tokename: string, decimals: number, domainname: string,
        description: string, amount: BigInteger) {
        try {
            wallet.setServerURL(contextRoot);
            await createToken(key, tokename, decimals, domainname, description, amount, true, null,
                TokenType.identity, key.getPublicKeyAsHex(), wallet);
            const signkey = ECKey.fromPrivate(Utils.HEX.decode(testPriv));
            await wallet.multiSign(key.getPublicKeyAsHex(), signkey, null);
        } catch (e) {
            console.warn("", e);
        }
    }

    async function createToken(key: ECKey, tokename: string, decimals: number, domainname: string, description: string,
        amount: BigInteger, increment: boolean, tokenKeyValues: TokenKeyValues | null, tokentype: TokenType, tokenid: string,
        w: Wallet): Promise<Block> {
        await w.importKey(key);
        const token = Token.buildSimpleTokenInfo(true, Sha256Hash.ZERO_HASH, tokenid, tokename, description, 1, 0,
            amount, !increment, decimals, "");
        token.setTokenKeyValues(tokenKeyValues);
        token.setTokentype(tokentype);
        const addresses: MultiSignAddress[] = [];
        addresses.push(new MultiSignAddress(tokenid, "", key.getPublicKeyAsHex()));
        return w.createToken(key, domainname, increment, token, addresses);
    }
});
