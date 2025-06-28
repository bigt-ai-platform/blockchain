// Enum for protocol versions, adapted from Java
// net.bigtangle.params.ProtocolVersion

export enum ProtocolVersion {
    MINIMUM = 70000,
    PONG = 60001,
    CURRENT = 70001
}

// Alias for compatibility with Java code
export const BLOOM_FILTER = ProtocolVersion.MINIMUM;
