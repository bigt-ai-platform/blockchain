import { NetworkParameters } from './NetworkParameters';

export enum BlockType {
    BLOCKTYPE_INITIAL,
    BLOCKTYPE_TRANSFER,
    BLOCKTYPE_REWARD,
    BLOCKTYPE_TOKEN_CREATION,
    BLOCKTYPE_USERDATA,
    BLOCKTYPE_CONTRACT_EVENT,
    BLOCKTYPE_GOVERNANCE,
    BLOCKTYPE_FILE,
    BLOCKTYPE_CONTRACT_EXECUTE,
    BLOCKTYPE_CROSSTANGLE,
    BLOCKTYPE_ORDER_OPEN,
    BLOCKTYPE_ORDER_CANCEL,
    BLOCKTYPE_ORDER_EXECUTE,
    BLOCKTYPE_CONTRACTEVENT_CANCEL
}

export interface BlockTypeConfig {
    allowCoinbaseTransaction: boolean;
    maxSize: number;
    requiresCalculation: boolean;
}

export const BlockTypeConfigs: { [key in BlockType]: BlockTypeConfig } = {
    [BlockType.BLOCKTYPE_INITIAL]: { allowCoinbaseTransaction: false, maxSize: Number.MAX_SAFE_INTEGER, requiresCalculation: false },
    [BlockType.BLOCKTYPE_TRANSFER]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_REWARD]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_REWARD_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_TOKEN_CREATION]: { allowCoinbaseTransaction: true, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_USERDATA]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_CONTRACT_EVENT]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_GOVERNANCE]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_FILE]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_CONTRACT_EXECUTE]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_CROSSTANGLE]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_ORDER_OPEN]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_ORDER_CANCEL]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_ORDER_EXECUTE]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
    [BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL]: { allowCoinbaseTransaction: false, maxSize: NetworkParameters.MAX_DEFAULT_BLOCK_SIZE, requiresCalculation: false },
};

export function allowCoinbaseTransaction(type: BlockType): boolean {
    return BlockTypeConfigs[type].allowCoinbaseTransaction;
}

export function getMaxBlockSize(type: BlockType): number {
    return BlockTypeConfigs[type].maxSize;
}

export function requiresCalculation(type: BlockType): boolean {
    return BlockTypeConfigs[type].requiresCalculation;
}
