import { Coin } from './Coin';
import { Script } from '../script/Script';
import { Sha256Hash } from './Sha256Hash';
import { MemoInfo } from './MemoInfo';
import { Utils } from '../utils/Utils';
import { Address } from './Address';
import { SpentBlock } from './SpentBlock';

/**
 * A UTXO message contains the information necessary to check a spending
 * transaction.
 */
export class UTXO extends SpentBlock {
    private value: Coin | null = null;
    private script: Script | null = null;
    private hash: Sha256Hash | null = null;
    private index: number = 0;
    private coinbase: boolean = false;
    private address: string | null = null;
    private fromaddress: string | null = null;
    private spendPending: boolean = false;
    private spendPendingTime: number = 0;
    private tokenId: string | null = null;
    private minimumsign: number = 0;
    private memo: string | null = null;
   
    constructor() {
        super();
    }

    public keyAsString(): string {
        return `${this.getBlockHashHex()}-${Utils.HEX.encode(this.hash ? this.hash.getBytes() : new Uint8Array())}-${this.index}`;
    }

    public getSpendPendingTime(): number {
        return this.spendPendingTime;
    }

    public isZero(): boolean {
        return this.value !== null && this.value.isZero();
    }

    public setSpendPendingTime(spendPendingTime: number): void {
        this.spendPendingTime = spendPendingTime;
    }

    public setScriptHex(scriptHex: string): void {
        this.script = new Script(Utils.HEX.decode(scriptHex));
    }

    public setHashHex(hashHex: string): void {
        this.hash = Sha256Hash.wrap(hashHex);
    }

    public setValue(value: Coin): void {
        this.value = value;
    }

    public setHash(hash: Sha256Hash): void {
        this.hash = hash;
    }

    public setIndex(index: number): void {
        this.index = index;
    }

    public setCoinbase(coinbase: boolean): void {
        this.coinbase = coinbase;
    }

    public setAddress(address: string): void {
        this.address = address;
    }

    public isMultiSig(): boolean {
        return this.minimumsign > 1;
    }

    public getTokenId(): string | null {
        return this.tokenId;
    }

    public setTokenid(tokenid: string): void {
        this.tokenId = tokenid;
    }

    public getTokenidBuf(): Uint8Array {
        return this.tokenId ? Utils.HEX.decode(this.tokenId) : new Uint8Array();
    }

    public getFromaddress(): string | null {
        return this.fromaddress;
    }

    public setFromaddress(fromaddress: string): void {
        this.fromaddress = fromaddress;
    }

    public memoToString(): string | null {
        return this.memo ? MemoInfo.parseToString(this.memo) : null;
    }

    public getValue(): Coin | null {
        return this.value;
    }

    public getScript(): Script | null {
        return this.script;
    }

    public setScript(script: Script): void {
        this.script = script;
    }

    public getScriptHex(): string {
        return this.script ? Utils.HEX.encode(this.script.getProgram()) : "";
    }

    public getTxHash(): Sha256Hash | null {
        return this.hash;
    }

    public getHashHex(): string {
        return this.hash ? Utils.HEX.encode(this.hash.getBytes()) : "";
    }

    public getIndex(): number {
        return this.index;
    }

    public isCoinbase(): boolean {
        return this.coinbase;
    }

    public getAddress(): string | null {
        return this.address;
    }

    public toStringShort(): string {
        return `UTXO ${this.value} (${this.hash}:${this.index})`;
    }

    public toString(): string {
        return `UTXO [value=${this.value}, \n script=${this.script}, \n hash=${this.hash}, \n index=${this.index}` +
               `, coinbase=${this.coinbase}, \n address=${this.address}, \n fromaddress=${this.fromaddress}` +
               `, \n time=${Utils.dateTimeFormat(this.getTime() * 1000)}` +
               `, \n memo=${this.memo} \n confirmed=${this.isConfirmed()}` +
               `, \n spent=${this.isSpent()} \n spendPending=${this.spendPending}` +
               `, \n spendPendingTime=${this.spendPendingTime}` +
               `, \n spenderBlockHash=${this.getSpenderBlockHash()} \n tokenId=${this.tokenId}` +
               `, \n minimumsign=${this.minimumsign} \n ]`;
    }

    public hashCode(): number {
        let result = 17;
        result = 31 * result + this.index;
        result = 31 * result + (this.hash ? this.hash.hashCode() : 0);
        result = 31 * result + (this.getBlockHash() ? this.getBlockHash()!.hashCode() : 0);
        return result;
    }

    public equals(o: any): boolean {
        if (this === o) return true;
        if (o === null || !(o instanceof UTXO)) return false;
        const other = o as UTXO;
        return this.getIndex() === other.getIndex() &&
               (this.getTxHash() === other.getTxHash() || (this.getTxHash() !== null && other.getTxHash() !== null && this.getTxHash()!.equals(other.getTxHash()!))) &&
               (this.getBlockHash() === other.getBlockHash() || (this.getBlockHash() !== null && other.getBlockHash() !== null && this.getBlockHash()!.equals(other.getBlockHash()!)));
    }

    public getMemo(): string | null {
        return this.memo;
    }

    public setMemo(memo: string): void {
        this.memo = memo;
    };

    public isSpendPending(): boolean {
        return this.spendPending;
    }

    public setSpendPending(spendPending: boolean): void {
        this.spendPending = spendPending;
    }

    public getMinimumsign(): number {
        return this.minimumsign;
    }

    public setMinimumsign(minimumsign: number): void {
        this.minimumsign = minimumsign;
    }

    constructor(
        hash: Sha256Hash,
        index: number,
        value: Coin,
        coinbase: boolean,
        script: Script,
        address: string,
        blockhash: Sha256Hash,
        fromaddress: string,
        memo: string,
        tokenid: string,
        spent: boolean,
        confirmed: boolean,
        spendPending: boolean,
        minimumsign: number,
        spendPendingTime: number,
        time: number,
        spenderBlockHash: Sha256Hash
    ) {
        super();
        this.hash = hash;
        this.index = index;
        this.value = value;
        this.script = script;
        this.coinbase = coinbase;
        this.setBlockHash(blockhash);
        this.fromaddress = fromaddress;
        this.memo = memo;
        this.address = address;
        this.setSpent(spent);
        this.setSpenderBlockHash(spenderBlockHash);
        this.tokenId = tokenid;
        this.setConfirmed(confirmed);
        this.spendPending = spendPending;
        this.minimumsign = minimumsign;
        this.spendPendingTime = spendPendingTime;
        this.setTime(time);
    }
}