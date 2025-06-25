import { NetworkParameters } from './NetworkParameters';

export class TestNetParams implements NetworkParameters {
    getId(): string { return "test"; }
    getAddressHeader(): number { return 111; }
    getP2SHHeader(): number { return 196; }
    getDumpedPrivateKeyHeader(): number { return 239; }
    getMaxTarget(): bigint { return BigInt("0x00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff"); }
    getProtocolVersionNum(): number { return 70001; }
    getPacketMagic(): number { return 0x0b110907; }
    getPort(): number { return 18333; }
    getAcceptableAddressCodes(): number[] { return [this.getAddressHeader(), this.getP2SHHeader()]; }
    getBip32HeaderPub(): number { return 0x043587cf; }
    getBip32HeaderPriv(): number { return 0x04358394; }
    getInterval(): number { return 2016; }
    getTargetTimespan(): number { return 14 * 24 * 60 * 60; } // 2 weeks
    getSpendableCoinbaseDepth(): number { return 100; }
    getSubsidyDecreaseBlockCount(): number { return 210000; }
    getProofOfWorkLimit(): bigint { return this.getMaxTarget(); }
}
