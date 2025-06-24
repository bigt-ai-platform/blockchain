import { Sha256Hash } from './Sha256Hash';
import { Coin } from './Coin';

export class UTXO {
    private blockHash: Sha256Hash;
    private txHash: Sha256Hash;
    private index: number;
    private value: Coin;
    private scriptBytes: Uint8Array;
    private address: string;
    private spendPending: boolean;
    private spendPendingTime: number;
    private multiSig: boolean;
    private tokenId: string; // Assuming tokenId is a string representation of Uint8Array

    constructor(
        blockHash: Sha256Hash,
        txHash: Sha256Hash,
        index: number,
        value: Coin,
        scriptBytes: Uint8Array,
        address: string,
        spendPending: boolean,
        spendPendingTime: number,
        multiSig: boolean,
        tokenId: string
    ) {
        this.blockHash = blockHash;
        this.txHash = txHash;
        this.index = index;
        this.value = value;
        this.scriptBytes = scriptBytes;
        this.address = address;
        this.spendPending = spendPending;
        this.spendPendingTime = spendPendingTime;
        this.multiSig = multiSig;
        this.tokenId = tokenId;
    }

    getBlockHash(): Sha256Hash {
        return this.blockHash;
    }

    setBlockHash(blockHash: Sha256Hash): void {
        this.blockHash = blockHash;
    }

    getTxHash(): Sha256Hash {
        return this.txHash;
    }

    setTxHash(txHash: Sha256Hash): void {
        this.txHash = txHash;
    }

    getIndex(): number {
        return this.index;
    }

    setIndex(index: number): void {
        this.index = index;
    }

    getValue(): Coin {
        return this.value;
    }

    setValue(value: Coin): void {
        this.value = value;
    }

    getScriptBytes(): Uint8Array {
        return this.scriptBytes;
    }

    setScriptBytes(scriptBytes: Uint8Array): void {
        this.scriptBytes = scriptBytes;
    }

    getAddress(): string {
        return this.address;
    }

    setAddress(address: string): void {
        this.address = address;
    }

    isSpendPending(): boolean {
        return this.spendPending;
    }

    setSpendPending(spendPending: boolean): void {
        this.spendPending = spendPending;
    }

    getSpendPendingTime(): number {
        return this.spendPendingTime;
    }

    setSpendPendingTime(spendPendingTime: number): void {
        this.spendPendingTime = spendPendingTime;
    }

    isMultiSig(): boolean {
        return this.multiSig;
    }

    setMultiSig(multiSig: boolean): void {
        this.multiSig = multiSig;
    }

    getTokenId(): string {
        return this.tokenId;
    }

    setTokenId(tokenId: string): void {
        this.tokenId = tokenId;
    }
}
