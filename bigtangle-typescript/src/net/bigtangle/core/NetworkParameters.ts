export interface NetworkParameters {
    getId(): string;
    getAddressHeader(): number;
    getP2SHHeader(): number;
    getDumpedPrivateKeyHeader(): number;
    getMaxTarget(): bigint;
    getProtocolVersionNum(): number;
    getPacketMagic(): number;
    getPort(): number;
    getAcceptableAddressCodes(): number[];
    getBip32HeaderPub(): number;
    getBip32HeaderPriv(): number;
    getInterval(): number;
    getTargetTimespan(): number;
    getSpendableCoinbaseDepth(): number;
    getSubsidyDecreaseBlockCount(): number;
    getProofOfWorkLimit(): bigint;
}
