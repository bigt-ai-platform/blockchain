import { MessageSerializer } from '../core/MessageSerializer';
import { BitcoinSerializer } from '../core/BitcoinSerializer';
 
// Define or import ProtocolVersion type
export type ProtocolVersion = number;

export abstract class NetworkParameters {
    static readonly ID_MAINNET = 'Mainnet';
    static readonly ID_UNITTESTNET = 'Test';

    maxTarget!: bigint;
    maxTargetReward!: bigint;
    addressHeader!: number;
    p2shHeader!: number;
    dumpedPrivateKeyHeader!: number;
    acceptableAddressCodes!: number[];
    bip32HeaderPub!: number;
    bip32HeaderPriv!: number;
    packetMagic!: number;
    alertSigningKey?: Uint8Array;
    id!: string;
    spendableCoinbaseDepth!: number;
    dnsSeeds!: string[];
    genesisPub!: string;
    permissionDomainname!: string[];

    // MCMC settings
    static readonly CONFIRMATION_UPPER_THRESHOLD_PERCENT = 51;
    static readonly CONFIRMATION_LOWER_THRESHOLD_PERCENT = 45;
    static readonly NUMBER_RATING_TIPS = 10;
    static readonly CONFIRMATION_UPPER_THRESHOLD = Math.floor(NetworkParameters.CONFIRMATION_UPPER_THRESHOLD_PERCENT * NetworkParameters.NUMBER_RATING_TIPS / 100);
    static readonly CONFIRMATION_LOWER_THRESHOLD = Math.floor(NetworkParameters.CONFIRMATION_LOWER_THRESHOLD_PERCENT * NetworkParameters.NUMBER_RATING_TIPS / 100);

    // Token ID for System Coin
    static readonly BIGTANGLE_TOKENID_STRING = 'bc';
    static readonly BIGTANGLE_TOKENNAME = 'BIG';
    static readonly BIGTANGLE_DECIMAL = 6;

    static readonly BLOCK_VERSION_GENESIS = 1;
    static readonly MAX_DEFAULT_BLOCK_SIZE = 1024 * 1024;
    static readonly MAX_BLOCK_SIGOPS = NetworkParameters.MAX_DEFAULT_BLOCK_SIZE / 50;
    static readonly ALLOWED_TIME_DRIFT = 5 * 60;
    static readonly HEADER_SIZE = 88 + 32 + 2 * 4 + 8 + 20 + 4 + 8;
    static readonly ORDER_TIMEOUT_MAX = 8 * 60 * 60;
    static readonly BigtangleCoinTotal = BigInt('1' + '0'.repeat(11 + NetworkParameters.BIGTANGLE_DECIMAL));
    static readonly TARGET_YEARLY_MINING_PAYOUT = Number(NetworkParameters.BigtangleCoinTotal) / 1000;
    static readonly TARGET_TIMESPAN = 3 * 60 * 60;
    static readonly TARGET_SPACING = 30;
    static readonly INTERVAL = Math.floor(NetworkParameters.TARGET_TIMESPAN / NetworkParameters.TARGET_SPACING);
    static readonly TARGET_MAX_TPS = 100;
    static readonly TARGET_INTERVAL_REWARD = Math.floor(NetworkParameters.TARGET_YEARLY_MINING_PAYOUT * NetworkParameters.TARGET_SPACING / 31536000);
    static readonly REWARD_AMOUNT_BLOCK_REWARD = Math.floor(NetworkParameters.TARGET_INTERVAL_REWARD / 3);
    static readonly PER_BLOCK_REWARD = Math.floor(NetworkParameters.TARGET_INTERVAL_REWARD / 3 / NetworkParameters.TARGET_MAX_TPS / NetworkParameters.TARGET_SPACING);
    static readonly TARGET_MAX_BLOCKS_IN_REWARD = 5000;
    static readonly MAX_REWARD_BLOCK_SIZE = NetworkParameters.MAX_DEFAULT_BLOCK_SIZE + NetworkParameters.TARGET_MAX_BLOCKS_IN_REWARD * 200;
    static readonly MILESTONE_CUTOFF = 40;
    static readonly FORWARD_BLOCK_HORIZON = Math.floor(NetworkParameters.TARGET_MAX_BLOCKS_IN_REWARD / 4);

    protected constructor() {}

    getId(): string {
        return this.id;
    }

    equals(other: NetworkParameters): boolean {
        return this.getId() === other.getId();
    }

    getSpendableCoinbaseDepth(): number {
        return this.spendableCoinbaseDepth;
    }

    getDnsSeeds(): string[] {
        return this.dnsSeeds;
    }

    getP2SHHeader(): number {
        return this.p2shHeader;
    }

    getPacketMagic(): number {
        return this.packetMagic;
    }

    getDumpedPrivateKeyHeader(): number {
        return this.dumpedPrivateKeyHeader;
    }

    getMaxTarget(): bigint {
        return this.maxTarget;
    }

    getMaxTargetReward(): bigint {
        return this.maxTargetReward;
    }

    getBip32HeaderPub(): number {
        return this.bip32HeaderPub;
    }

    getBip32HeaderPriv(): number {
        return this.bip32HeaderPriv;
    }

    getOrderPriceShift(orderBaseTokens: string): number {
        if (orderBaseTokens === NetworkParameters.BIGTANGLE_TOKENID_STRING) {
            return 0;
        } else {
            return 6;
        }
    }

    getProtocolVersionNum(version: ProtocolVersion): number {
        return version;
    }

    getGenesisPub(): string {
        return this.genesisPub;
    }

    getAddressHeader(): number {
        return this.addressHeader;
    }

    getAcceptableAddressCodes(): number[] {
        return this.acceptableAddressCodes;
    }

    getAlertSigningKey(): Uint8Array | undefined {
        return this.alertSigningKey;
    }

    abstract serverSeeds(): string[];

    private defaultSerializer?: MessageSerializer;

    /**
     * Return the default serializer for this network. This is a shared serializer.
     */
    public getDefaultSerializer(): MessageSerializer {
        this.defaultSerializer ??= new BitcoinSerializer(this, false);
        return this.defaultSerializer;
    }

    public getSerializer(parseRetain: boolean): MessageSerializer {
        return new BitcoinSerializer(this, parseRetain);
    }
}
