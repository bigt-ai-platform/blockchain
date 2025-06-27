import { DeterministicKeyChain } from './DeterministicKeyChain';
import { RedeemData } from './RedeemData';
import { DeterministicKey } from '../crypto/DeterministicKey';
import { KeyPurpose } from './KeyChain';
import * as Protos from './Protos';
import { BloomFilter } from '../core/BloomFilter';
import { Script } from '../script/Script';
import { NetworkParameters } from '../core/NetworkParameters';
import { ScriptBuilder } from '../script/ScriptBuilder';
import { Utils } from '../utils/Utils';

export class MarriedKeyChain extends DeterministicKeyChain {
    public sigsRequiredToSpend: number = 0;
    private readonly marriedKeysRedeemData: Map<string, RedeemData> = new Map();
    private followingKeyChains: DeterministicKeyChain[] = [];
    private params: NetworkParameters;

    constructor(params: NetworkParameters, ...args: any[]) {
        super(args[0]);
        this.params = params;
    }

    public isMarried(): boolean { return true; }

    public freshOutputScript(purpose: KeyPurpose): Script {
        const followedKey = this.getKey(purpose);
        const keys: any[] = [followedKey];
        for (const keyChain of this.followingKeyChains) {
            const followingKey = keyChain.getKey(purpose);
            if (typeof followedKey.getChildNumber === 'function' &&
                !followedKey.getChildNumber().equals(followingKey.getChildNumber())) {
                throw new Error("Following keychains should be in sync");
            }
            keys.push(followingKey);
        }
        const redeemScript = ScriptBuilder.createRedeemScript(this.sigsRequiredToSpend, keys);
        return ScriptBuilder.createP2SHOutputScript(redeemScript.getProgram());
    }

    private getMarriedKeysWithFollowed(followedKey: DeterministicKey): any[] {
        const keys: any[] = [];
        for (const keyChain of this.followingKeyChains) {
            if (typeof keyChain.maybeLookAhead === 'function') keyChain.maybeLookAhead();
            // Use 0 as default purpose if not available
            keys.push(keyChain.getKey(0));
        }
        keys.push(followedKey);
        return keys;
    }

    public getRedeemData(followedKey: DeterministicKey): RedeemData {
        const marriedKeys = this.getMarriedKeysWithFollowed(followedKey);
        const redeemScript = ScriptBuilder.createRedeemScript(this.sigsRequiredToSpend, marriedKeys);
        return RedeemData.of(marriedKeys, redeemScript);
    }

    public addFollowingAccountKeys(followingAccountKeys: DeterministicKey[], sigsRequiredToSpend: number): void {
        if (sigsRequiredToSpend > followingAccountKeys.length + 1) {
            throw new Error("Multisig threshold can't exceed total number of keys");
        }
        if (typeof this.numLeafKeysIssued === 'function' && this.numLeafKeysIssued() > 0) {
            throw new Error("Active keychain already has keys in use");
        }
        if (this.followingKeyChains && this.followingKeyChains.length > 0) {
            throw new Error("Following keychains already set");
        }
        // Fallback: skip getAccountPath and watchAndFollow, just push as is
        this.sigsRequiredToSpend = sigsRequiredToSpend;
        this.followingKeyChains = followingAccountKeys.map(key => new DeterministicKeyChain(key));
    }

    public setLookaheadSize(lookaheadSize: number): void {
        if (this.followingKeyChains) {
            for (const followingChain of this.followingKeyChains) {
                if ((followingChain as any).setLookaheadSize) (followingChain as any).setLookaheadSize(lookaheadSize);
            }
        }
    }

    public serializeToProtobuf(): Protos.Key[] {
        const result: Protos.Key[] = [];
        if (this.followingKeyChains) {
            for (const chain of this.followingKeyChains) {
                if ((chain as any).serializeMyselfToProtobuf) {
                    result.push(...(chain as any).serializeMyselfToProtobuf());
                }
            }
        }
        if ((this as any).serializeMyselfToProtobuf) {
            result.push(...(this as any).serializeMyselfToProtobuf());
        }
        return result;
    }

    protected formatAddresses(includePrivateKeys: boolean, params: NetworkParameters, builder: string): string {
        let out = builder;
        for (const followingChain of this.followingKeyChains) {
            out += `Following chain:  ${followingChain.getWatchingKey().serializePubB58(params)}\n`;
        }
        out += '\n';
        for (const redeemData of this.marriedKeysRedeemData.values()) {
            out = this.formatScript(ScriptBuilder.createP2SHOutputScript(redeemData.redeemScript.getProgram()), out, params);
        }
        return out;
    }

    private formatScript(script: Script, builder: string, params: NetworkParameters): string {
        let out = builder;
        out += `  addr:${script.getToAddress(params)}`;
        out += `  hash160:${Utils.HEX.encode(script.getPubKeyHash())}`;
        if ((script as any).getCreationTimeSeconds && (script as any).getCreationTimeSeconds() > 0) {
            out += `  creationTimeSeconds:${(script as any).getCreationTimeSeconds()}`;
        }
        out += '\n';
        return out;
    }

    public maybeLookAheadScripts(): void {
        super.maybeLookAheadScripts();
        const numLeafKeys = typeof (this as any).getLeafKeys === 'function' ? (this as any).getLeafKeys().length : 0;
        if (this.marriedKeysRedeemData.size > numLeafKeys) {
            throw new Error("Number of scripts is greater than number of leaf keys");
        }
        if (this.marriedKeysRedeemData.size === numLeafKeys) return;
        if ((this as any).maybeLookAhead) (this as any).maybeLookAhead();
        if ((this as any).getLeafKeys) {
            for (const followedKey of (this as any).getLeafKeys()) {
                const redeemData = this.getRedeemData(followedKey);
                const scriptPubKey = ScriptBuilder.createP2SHOutputScript(redeemData.redeemScript.getProgram());
                this.marriedKeysRedeemData.set(Buffer.from(scriptPubKey.getPubKeyHash()).toString('hex'), redeemData);
            }
        }
    }

    public findRedeemDataByScriptHash(bytes: Uint8Array): RedeemData | null {
        return this.marriedKeysRedeemData.get(Buffer.from(bytes).toString('hex')) || null;
    }

    public getFilter(size: number, falsePositiveRate: number, tweak: number): BloomFilter {
        const filter = new BloomFilter(this.params, Buffer.alloc(size));
        for (const [key, redeemData] of this.marriedKeysRedeemData.entries()) {
            filter.insert(Buffer.from(key, 'hex'));
            filter.insert(redeemData.redeemScript.getProgram());
        }
        return filter;
    }

    public numBloomFilterEntries(): number {
        if ((this as any).maybeLookAhead) (this as any).maybeLookAhead();
        return typeof (this as any).getLeafKeys === 'function' ? ((this as any).getLeafKeys().length) * 2 : 0;
    }
}
