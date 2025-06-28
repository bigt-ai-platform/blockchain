import { Block } from './Block';
import { Sha256Hash } from './Sha256Hash';
 
import { ScriptBuilder } from '../script/ScriptBuilder';
import { Utils } from '../utils/Utils';
import { BitcoinSerializer } from './BitcoinSerializer'; // Import BitcoinSerializer directly
import { BigInteger } from './BigInteger';
import { ProtocolVersion } from './ProtocolVersion';
import { Transaction } from './Transaction';
import { TransactionInput } from './TransactionInput';
import { RewardInfo } from './RewardInfo';
import { ECKey } from './ECKey';
import { TransactionOutput } from './TransactionOutput';
import { Coin } from './Coin';
export abstract class NetworkParameters {
    static readonly ID_MAINNET = 'Mainnet';
    static readonly ID_UNITTESTNET = 'Test';
    protected    defaultSerializer: any;
    protected genesisBlock: Block | null = null;
    protected maxTarget: BigInteger = new BigInteger('0');
    protected maxTargetReward: BigInteger = new BigInteger('0');
    protected packetMagic: number = 0;
    protected addressHeader: number = 0;
    protected p2shHeader: number = 0;
    protected dumpedPrivateKeyHeader: number = 0;
    protected alertSigningKey: Uint8Array = new Uint8Array();
    protected bip32HeaderPub: number = 0;
    protected bip32HeaderPriv: number = 0;
    protected id: string = '';
    protected spendableCoinbaseDepth: number = 0;
    protected subsidyDecreaseBlockCount: number = 0;
    protected acceptableAddressCodes: number[] = [];
    protected dnsSeeds: string[] = [];
    protected addrSeeds: number[] = [];

    protected genesisPub: string = '';
    protected permissionDomainname: string[] = [];
  

    static readonly CONFIRMATION_UPPER_THRESHOLD_PERCENT = 51;
    static readonly CONFIRMATION_LOWER_THRESHOLD_PERCENT = 45;
    static readonly NUMBER_RATING_TIPS = 10;
    static readonly CONFIRMATION_UPPER_THRESHOLD = NetworkParameters.CONFIRMATION_UPPER_THRESHOLD_PERCENT * NetworkParameters.NUMBER_RATING_TIPS / 100;
    static readonly CONFIRMATION_LOWER_THRESHOLD = NetworkParameters.CONFIRMATION_LOWER_THRESHOLD_PERCENT * NetworkParameters.NUMBER_RATING_TIPS / 100;
    static readonly BIGTANGLE_TOKENID_STRING = 'bc';
    static readonly BIGTANGLE_TOKENNAME = 'BIG';
    static readonly BIGTANGLE_DECIMAL = 6;
    static readonly BLOCK_VERSION_GENESIS = 1;
    static readonly MAX_DEFAULT_BLOCK_SIZE = 1024 * 1024;
    static readonly MAX_BLOCK_SIGOPS = NetworkParameters.MAX_DEFAULT_BLOCK_SIZE / 50;
    static readonly BigtangleCoinTotal = "2100000000000000"; // Add this line, adjust value as needed
    static readonly ALLOWED_TIME_DRIFT = 5 * 60;
    static readonly HEADER_SIZE = 88 + 32 + 2 * 4 + 8 + 20 + 4 + 8;
    static readonly ORDER_TIMEOUT_MAX = 8 * 60 * 60;
    static readonly TARGET_TIMESPAN = 3 * 60 * 60;
    static readonly TARGET_SPACING = 30;
    static readonly INTERVAL = NetworkParameters.TARGET_TIMESPAN / NetworkParameters.TARGET_SPACING;
    static readonly TARGET_MAX_TPS = 100;
    static readonly TARGET_MAX_BLOCKS_IN_REWARD = 5000;
    static readonly MAX_REWARD_BLOCK_SIZE = NetworkParameters.MAX_DEFAULT_BLOCK_SIZE + NetworkParameters.TARGET_MAX_BLOCKS_IN_REWARD * 200;
    static readonly MILESTONE_CUTOFF = 40;
    static readonly FORWARD_BLOCK_HORIZON = NetworkParameters.TARGET_MAX_BLOCKS_IN_REWARD / 2 * 2;

    getId(): string { return this.id; }
    getSpendableCoinbaseDepth(): number { return this.spendableCoinbaseDepth; }
    getDnsSeeds(): string[] { return this.dnsSeeds; }
    getAddrSeeds(): number[] { return this.addrSeeds; }
    getGenesisBlock(): Block | null { return NetworkParameters.createGenesis(this); }
   
    getPacketMagic(): number { return this.packetMagic; }
    getAddressHeader(): number { return this.addressHeader; }
    getP2SHHeader(): number { return this.p2shHeader; }
    getDumpedPrivateKeyHeader(): number { return this.dumpedPrivateKeyHeader; }
    getAcceptableAddressCodes(): number[] { return this.acceptableAddressCodes; }
    getMaxTarget(): bigint { return this.maxTarget; }
    getMaxTargetReward(): bigint { return this.maxTargetReward; }
    getAlertSigningKey(): Uint8Array { return this.alertSigningKey; }
    getBip32HeaderPub(): number { return this.bip32HeaderPub; }
    getBip32HeaderPriv(): number { return this.bip32HeaderPriv; }
    getSubsidyDecreaseBlockCount(): number { return this.subsidyDecreaseBlockCount; }
 
    getPermissionDomainname(): string[] { return this.permissionDomainname; }
    getGenesisPub(): string { return this.genesisPub; }
    getDefaultSerializer(): any {
        this.defaultSerializer ??= new BitcoinSerializer(this, false);
        return this.defaultSerializer;
    }
    getOrderTimeoutMax(): number { return NetworkParameters.ORDER_TIMEOUT_MAX; }
    getTargetTimespan(): number { return NetworkParameters.TARGET_TIMESPAN; }
    getTargetSpacing(): number { return NetworkParameters.TARGET_SPACING; }
    getInterval(): number { return NetworkParameters.INTERVAL; }
    getTargetMaxTps(): number { return NetworkParameters.TARGET_MAX_TPS; }
    getTargetMaxBlocksInReward(): number { return NetworkParameters.TARGET_MAX_BLOCKS_IN_REWARD; }
    getMaxRewardBlockSize(): number { return NetworkParameters.MAX_REWARD_BLOCK_SIZE; }
    getMilestoneCutoff(): number { return NetworkParameters.MILESTONE_CUTOFF; }
    getForwardBlockHorizon(): number { return NetworkParameters.FORWARD_BLOCK_HORIZON; }

    static createGenesis(params: NetworkParameters): Block {
        const genesisBlock = new Block(
            params,
            (Sha256Hash as any).ZERO_HASH, // adapt as needed
            (Sha256Hash as any).ZERO_HASH,
            0, // Block.Type.BLOCKTYPE_INITIAL.ordinal() or equivalent
            0,
            0,
            Utils.encodeCompactBits(new BigInteger(params.getMaxTarget().toString()))
        );
        genesisBlock.setTime(1532896109);
        genesisBlock.setDifficultyTarget(Utils.encodeCompactBits(new BigInteger(params.getMaxTarget().toString())));
        const coinbase = new Transaction(params);
        const inputBuilder = new ScriptBuilder();
        coinbase.addInput(new TransactionInput(params, coinbase, Buffer.from(inputBuilder.build().getProgram())));
        const rewardInfo = new RewardInfo((Sha256Hash as any).ZERO_HASH, Utils.encodeCompactBits(new BigInteger(params.getMaxTargetReward().toString())), new Set(), 0);
        coinbase.setData(Buffer.from(rewardInfo.toByteArray()));
        // Add the coinbase output to the transaction
        const scriptPubKey = ScriptBuilder.createOutputScript(ECKey.fromPublic(params.genesisPub));
        coinbase.addOutput(new TransactionOutput(
            params,
            coinbase,
            new Coin(BigInt(NetworkParameters.BigtangleCoinTotal.toString()), Buffer.from(NetworkParameters.BIGTANGLE_TOKENID_STRING)),
            Buffer.from(scriptPubKey.getProgram())
        ));
        genesisBlock.addTransaction(coinbase);
        genesisBlock.setNonce(0);
        genesisBlock.setHeight(0);
        return genesisBlock;
    }

    // Static utility: fromID
    static fromID(id: string): NetworkParameters | null {
        if (id === NetworkParameters.ID_MAINNET) {
            return (require('../params/MainNetParams').MainNetParams).get();
        } else if (id === NetworkParameters.ID_UNITTESTNET) {
            return (require('../params/MainNetParams').MainNetParams).get();
        } else {
            return null;
        }
    }

    // Static utility: add
    static add(params: NetworkParameters, amount: bigint, account: string, coinbase: any): void {
        const list = account.split(',');
        const base = new Coin(amount, Buffer.from(NetworkParameters.BIGTANGLE_TOKENID_STRING));
        const keys: any[] = [];
        for (const s of list) {
            keys.push(ECKey.fromPublicOnly((Utils as any).HEX.decode(s.trim())));
        }
        if (keys.length <= 1) {
            coinbase.addOutput(new TransactionOutput(params, coinbase, base, Buffer.from(ScriptBuilder.createOutputScript(keys[0]).getProgram())));
        } else {
            const scriptPubKey = ScriptBuilder.createMultiSigOutputScript(keys.length - 1, keys);
            coinbase.addOutput(new TransactionOutput(params, coinbase, base, Buffer.from(scriptPubKey.getProgram())));
        }
    }

    // Instance methods (getters) already implemented above
    // equals and hashCode
    equals(o: any): boolean {
        if (this === o) return true;
        if (o === null || !(o instanceof NetworkParameters)) return false;
        return this.getId() === o.getId();
    }

    hashCode(): number {
        // Simple hash for string id
        let hash = 0, i, chr;
        const str = this.getId();
        if (str.length === 0) return hash;
        for (i = 0; i < str.length; i++) {
            chr = str.charCodeAt(i);
            hash = ((hash << 5) - hash) + chr;
            hash |= 0; // Convert to 32bit integer
        }
        return hash;
    }

    // getTransactionVerificationFlags (returns a Set or array of flags)
    getTransactionVerificationFlags(): Set<string> {
        // This is a simplification; adapt as needed for your Script.VerifyFlag
        return new Set(['P2SH', 'CHECKLOCKTIMEVERIFY']);
    }
getSerializer(parseRetain: boolean): BitcoinSerializer {
    return new BitcoinSerializer(this, parseRetain);
}
    getProtocolVersionNum( ): number {
        return ProtocolVersion.CURRENT; // Replace with the correct property, e.g., BITCOIN, if it exists
    }

    // Abstract methods
    abstract getUriScheme(): string;
    
   
    abstract serverSeeds(): string[];
    abstract getOrderPriceShift(orderBaseTokens: string): number;
    // Static utility methods (createGenesis, add, fromID, etc.) can be added as needed
}
