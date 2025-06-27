export class MatchResult {
    private price: number = 0;
    private executedQuantity: number = 0;
    private txhash: string | null = null;
    private tokenid: string | null = null;
    private basetokenid: string | null = null;
    private inserttime: number = 0;

    constructor(
        txhash?: string,
        tokenid?: string,
        basetokenid?: string,
        price?: number,
        executedQuantity?: number,
        inserttime?: number
    ) {
        if (txhash) this.txhash = txhash;
        if (tokenid) this.tokenid = tokenid;
        if (basetokenid) this.basetokenid = basetokenid;
        if (price !== undefined) this.price = price;
        if (executedQuantity !== undefined) this.executedQuantity = executedQuantity;
        if (inserttime !== undefined) this.inserttime = inserttime;
    }

    public getPrice(): number {
        return this.price;
    }

    public setPrice(price: number): void {
        this.price = price;
    }

    public getExecutedQuantity(): number {
        return this.executedQuantity;
    }

    public setExecutedQuantity(executedQuantity: number): void {
        this.executedQuantity = executedQuantity;
    }

    public getTokenid(): string | null {
        return this.tokenid;
    }

    public setTokenid(tokenid: string | null): void {
        this.tokenid = tokenid;
    }

    public getInserttime(): number {
        return this.inserttime;
    }

    public setInserttime(inserttime: number): void {
        this.inserttime = inserttime;
    }

    public getTxhash(): string | null {
        return this.txhash;
    }

    public setTxhash(txhash: string | null): void {
        this.txhash = txhash;
    }

    public getBasetokenid(): string | null {
        return this.basetokenid;
    }

    public setBasetokenid(basetokenid: string | null): void {
        this.basetokenid = basetokenid;
    }

    public toString(): string {
        return `MatchResult [price=${this.price}, executedQuantity=${this.executedQuantity}, txhash=${this.txhash}` +
               `, tokenid=${this.tokenid}, basetokenid=${this.basetokenid}, inserttime=${this.inserttime}]`;
    }
}
