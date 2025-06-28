export enum ProtocolVersion {
    MINIMUM = 70000,
    PONG = 60001,
    CURRENT = 70001,
}

export const BLOOM_FILTER = ProtocolVersion.MINIMUM;

export function getBitcoinProtocolVersion(version: ProtocolVersion): number {
    return version;
}
