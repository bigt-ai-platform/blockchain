import { Sha256Hash } from './Sha256Hash';
import { BigInteger } from 'jsbn';
import { KeyValue } from './KeyValue';
import { TokenKeyValues } from './TokenKeyValues'; // Import TokenKeyValues

export class Token {
    private blockHash: Sha256Hash;
    private tokenid: string;
    private tokenname: string;
    private description: string;
    private signnumber: number;
    private tokenindex: number;
    private tokenstop: boolean;
    private decimals: number;
    private domainName: string;
    private domainNameBlockHash: string;
    private amount: BigInteger;
    private tokentype: number;
    private tokenKeyValues: TokenKeyValues | null = null; // Changed type to TokenKeyValues

    constructor(
        blockHash: Sha256Hash,
        tokenid: string,
        tokenname: string,
        description: string,
        signnumber: number,
        tokenindex: number,
        tokenstop: boolean,
        decimals: number,
        domainName: string,
        domainNameBlockHash: string,
        amount: BigInteger,
        tokentype: number,
        tokenKeyValues: TokenKeyValues | null = null // Added to constructor
    ) {
        this.blockHash = blockHash;
        this.tokenid = tokenid;
        this.tokenname = tokenname;
        this.description = description;
        this.signnumber = signnumber;
        this.tokenindex = tokenindex;
        this.tokenstop = tokenstop;
        this.decimals = decimals;
        this.domainName = domainName;
        this.domainNameBlockHash = domainNameBlockHash;
        this.amount = amount;
        this.tokentype = tokentype;
        this.tokenKeyValues = tokenKeyValues; // Assign in constructor
    }

    static buildSimpleTokenInfo(
        increment: boolean,
        blockHash: Sha256Hash,
        tokenid: string,
        tokenname: string,
        description: string,
        signnumber: number,
        tokenindex: number,
        amount: BigInteger,
        tokenstop: boolean,
        decimals: number,
        domainName: string,
        tokenKeyValues: TokenKeyValues | null = null // Added to static builder
    ): Token {
        return new Token(
            blockHash,
            tokenid,
            tokenname,
            description,
            signnumber,
            tokenindex,
            tokenstop,
            decimals,
            domainName,
            "", // domainNameBlockHash, will be set later
            amount,
            0, // tokentype, default to 0 or appropriate enum value
            tokenKeyValues // Pass to constructor
        );
    }

    static buildDomainnameTokenInfo(
        increment: boolean,
        blockHash: Sha256Hash,
        tokenid: string,
        tokenname: string,
        description: string,
        signnumber: number,
        tokenindex: number,
        tokenstop: boolean,
        domainName: string,
        domainNameBlockHash: string,
        tokenKeyValues: TokenKeyValues | null = null // Added to static builder
    ): Token {
        return new Token(
            blockHash,
            tokenid,
            tokenname,
            description,
            signnumber,
            tokenindex,
            tokenstop,
            0, // decimals, default to 0 for domain name
            domainName,
            domainNameBlockHash,
            BigInteger.ZERO, // amount, default to 0 for domain name
            0, // tokentype, default to 0 or appropriate enum value
            tokenKeyValues // Pass to constructor
        );
    }

    getBlockHash(): Sha256Hash {
        return this.blockHash;
    }

    setBlockHash(blockHash: Sha256Hash): void {
        this.blockHash = blockHash;
    }

    getTokenid(): string {
        return this.tokenid;
    }

    setTokenid(tokenid: string): void {
        this.tokenid = tokenid;
    }

    getTokenname(): string {
        return this.tokenname;
    }

    setTokenname(tokenname: string): void {
        this.tokenname = tokenname;
    }

    getDescription(): string {
        return this.description;
    }

    setDescription(description: string): void {
        this.description = description;
    }

    getSignnumber(): number {
        return this.signnumber;
    }

    setSignnumber(signnumber: number): void {
        this.signnumber = signnumber;
    }

    getTokenindex(): number {
        return this.tokenindex;
    }

    setTokenindex(tokenindex: number): void {
        this.tokenindex = tokenindex;
    }

    isTokenstop(): boolean {
        return this.tokenstop;
    }

    setTokenstop(tokenstop: boolean): void {
        this.tokenstop = tokenstop;
    }

    getDecimals(): number {
        return this.decimals;
    }

    setDecimals(decimals: number): void {
        this.decimals = decimals;
    }

    getDomainName(): string {
        return this.domainName;
    }

    setDomainName(domainName: string): void {
        this.domainName = domainName;
    }

    getDomainNameBlockHash(): string {
        return this.domainNameBlockHash;
    }

    setDomainNameBlockHash(domainNameBlockHash: string): void {
        this.domainNameBlockHash = domainNameBlockHash;
    }

    getAmount(): BigInteger {
        return this.amount;
    }

    setAmount(amount: BigInteger): void {
        this.amount = amount;
    }

    getTokentype(): number {
        return this.tokentype;
    }

    setTokentype(tokentype: number): void {
        this.tokentype = tokentype;
    }

    getTokenKeyValues(): TokenKeyValues | null { // Changed return type
        return this.tokenKeyValues;
    }

    setTokenKeyValues(tokenKeyValues: TokenKeyValues | null): void { // Changed parameter type
        this.tokenKeyValues = tokenKeyValues;
    }

    addKeyvalue(kv: KeyValue): void {
        if (this.tokenKeyValues == null) {
            this.tokenKeyValues = new TokenKeyValues(); // Initialize if null
        }
        this.tokenKeyValues.addKeyvalue(kv); // Add to the TokenKeyValues object
    }
}
