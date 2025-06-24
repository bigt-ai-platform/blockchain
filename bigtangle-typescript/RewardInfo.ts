import { Sha256Hash } from './Sha256Hash';

export class RewardInfo {
    private blockHash: Sha256Hash;
    private difficultyTarget: number;
    private rewardAddress: Set<string>; // Assuming Set<String> in Java maps to Set<string> in TS
    private rewardType: number;

    constructor(blockHash: Sha256Hash, difficultyTarget: number, rewardAddress: Set<string>, rewardType: number) {
        this.blockHash = blockHash;
        this.difficultyTarget = difficultyTarget;
        this.rewardAddress = rewardAddress;
        this.rewardType = rewardType;
    }

    toByteArray(): Uint8Array {
        // Simplified implementation for now.
        // In a real scenario, this would serialize the object to a byte array.
        return new Uint8Array();
    }

    parse(data: Uint8Array): RewardInfo {
        // Simplified parse implementation
        return this;
    }

    toString(): string {
        return `RewardInfo: blockHash=${this.blockHash.toString()}, difficultyTarget=${this.difficultyTarget}, rewardAddress=${Array.from(this.rewardAddress).join(',')}, rewardType=${this.rewardType}`;
    }
}
