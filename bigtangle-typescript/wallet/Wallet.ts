import { BigInteger } from 'jsbn';
import { Block } from '../Block';
import { Coin } from '../Coin';
import { ECKey } from '../ECKey';
import { MemoInfo } from '../MemoInfo';
import { MultiSignAddress } from '../MultiSignAddress';
import { NetworkParameters } from '../NetworkParameters';
import { Sha256Hash } from '../Sha256Hash';
import { Token } from '../Token';
import { TokenInfo } from '../TokenInfo';
import { Transaction } from '../Transaction';
import { TransactionInput } from '../TransactionInput';
import { TransactionOutput } from '../TransactionOutput';
import { Utils } from '../Utils';
import { ScriptBuilder } from '../ScriptBuilder';
import { Address } from '../Address';
import { OkHttp3Util } from '../utils/OkHttp3Util';
import { KeyParameter } from '../KeyParameter';

export class Wallet {
    protected params: NetworkParameters;
    protected keys: ECKey[] = [];
    protected serverURL: string;

    constructor(params: NetworkParameters, keys: ECKey[], url: string) {
        this.params = params;
        this.keys = keys;
        this.serverURL = url;
    }

    public static fromKeys(params: NetworkParameters, key: ECKey, url: string): Wallet {
        return new Wallet(params, [key], url);
    }

    public setServerURL(url: string) {
        this.serverURL = url;
    }

    public importKey(key: ECKey) {
        this.keys.push(key);
    }

    public async multiSign(tokenid: string, outKey: ECKey, aesKey: KeyParameter | null): Promise<Block | null> {
        // Dummy implementation
        return null;
    }

    public async createToken(key: ECKey, domainname: string, increment: boolean, token: Token,
        addresses: MultiSignAddress[]): Promise<Block> {
        // Dummy implementation
        return new Block(this.params, 1);
    }
}
