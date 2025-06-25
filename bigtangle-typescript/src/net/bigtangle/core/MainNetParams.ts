import { NetworkParameters } from './NetworkParameters';

export class MainNetParams implements NetworkParameters {
    getId(): string { return "main"; }
    getAddressHeader(): number { return 0; }
    getP2SHHeader(): number { return 5; }
    getDumpedPrivateKeyHeader(): number { return 128; }
    getMaxTarget(): bigint { return BigInt("0x00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff"); }
    getProtocolVersionNum(): number { return 70001; }
    getPacketMagic(): number { return 0xf9beb4d9; }
    getPort(): number { return 8333; }
    getAcceptableAddressCodes(): number[] { return [this.getAddressHeader(), this.getP2SHHeader()]; }
    getBip32HeaderPub(): number { return 0x0488b21e; }
    getBip32HeaderPriv(): number { return 0x0488ade4; }
    getInterval(): number { return 2016; }
    getTargetTimespan(): number { return 14 * 24 * 60 * 60; } // 2 weeks
    getSpendableCoinbaseDepth(): number { return 100; }
    getSubsidyDecreaseBlockCount(): number { return 210000; }
    getProofOfWorkLimit(): bigint { return this.getMaxTarget(); }
}
