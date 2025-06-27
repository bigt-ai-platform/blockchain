import { SpentBlock } from './SpentBlock';
import { Sha256Hash } from './Sha256Hash';
import { BigInteger } from 'jsbn';
import { TokenType } from './TokenType';
import { TokenKeyValues } from './TokenKeyValues';
import { NetworkParameters } from './NetworkParameters';
import { Utils } from '../utils/Utils';
import { KeyValue } from './KeyValue';
import { ECKey } from './ECKey';

export class Token extends SpentBlock {
    public static readonly TOKEN_MAX_NAME_LENGTH = 100;
    public static readonly TOKEN_MAX_DESC_LENGTH = 5000;
    public static readonly TOKEN_MAX_URL_LENGTH = 100;
    public static readonly TOKEN_MAX_ID_LENGTH = 100;
    public static readonly TOKEN_MAX_LANGUAGE_LENGTH = 2;
    public static readonly TOKEN_MAX_CLASSIFICATION_LENGTH = 100;

    private tokenid: string | null = null;
    private tokenindex: number = 0;
    private tokenname: string | null = null;
    private description: string | null = null;
    private domainName: string = "";
    private domainNameBlockHash: string | null = null;
    private signnumber: number = 0;
    private tokentype: number = 0;
    private tokenstop: boolean = false;
    private prevblockhash: Sha256Hash | null = null;
    private amount: BigInteger | null = null;
    private decimals: number = 0;
    private classification: string | null = null;
    private language: string | null = null;
    private revoked: boolean = false;
    private tokenKeyValues: TokenKeyValues | null = null;

    public addKeyvalue(kv: KeyValue): void {
        if (this.tokenKeyValues === null) {
            this.tokenKeyValues = new TokenKeyValues();
        }
        this.tokenKeyValues.addKeyvalue(kv);
    }

    public getTokenid(): string | null {
        return this.tokenid;
    }

    public setTokenid(tokenid: string | null): void {
        this.tokenid = tokenid;
    }

    public getTokenindex(): number {
        return this.tokenindex;
    }

    public setTokenindex(tokenindex: number): void {
        this.tokenindex = tokenindex;
    }

    public getAmount(): BigInteger | null {
        return this.amount;
    }

    public setAmount(amount: BigInteger | null): void {
        this.amount = amount;
    }

    public getTokenname(): string | null {
        return this.tokenname;
    }

    public setTokenname(tokenname: string | null): void {
        this.tokenname = tokenname;
    }

    public getDescription(): string | null {
        return this.description;
    }

    public setDescription(description: string | null): void {
        this.description = description;
    }

    public getDomainName(): string {
        if (this.domainName === null) {
            this.domainName = "";
        }
        return this.domainName;
    }

    public setDomainName(domainName: string): void {
        this.domainName = domainName;
    }

    public getDomainNameBlockHash(): string | null {
        return this.domainNameBlockHash;
    }

    public setDomainNameBlockHash(domainNameBlockHash: string | null): void {
        this.domainNameBlockHash = domainNameBlockHash;
    }

    public getRevoked(): boolean {
        return this.revoked;
    }

    public setRevoked(revoked: boolean): void {
        this.revoked = revoked;
    }

    public getSignnumber(): number {
        return this.signnumber;
    }

    public setSignnumber(signnumber: number): void {
        this.signnumber = signnumber;
    }

    public getTokentype(): number {
        return this.tokentype;
    }

    public setTokentype(tokentype: number): void {
        this.tokentype = tokentype;
    }

    public isTokenstop(): boolean {
        return this.tokenstop;
    }

    public setTokenstop(tokenstop: boolean): void {
        this.tokenstop = tokenstop;
    }

    public getPrevblockhash(): Sha256Hash | null {
        return this.prevblockhash;
    }

    public setPrevblockhash(prevblockhash: Sha256Hash | null): void {
        this.prevblockhash = prevblockhash;
    }

    public getTokenKeyValues(): TokenKeyValues | null {
        return this.tokenKeyValues;
    }

    public setTokenKeyValues(tokenKeyValues: TokenKeyValues | null): void {
        this.tokenKeyValues = tokenKeyValues;
    }

    public getClassification(): string | null {
        return this.classification;
    }

    public setClassification(classification: string | null): void {
        this.classification = classification;
    }

    public getLanguage(): string | null {
        return this.language;
    }

    public setLanguage(language: string | null): void {
        this.language = language;
    }

    public getDecimals(): number {
        return this.decimals;
    }

    public setDecimals(decimals: number): void {
        this.decimals = decimals;
    }

    public getTokenFullname(): string {
        if (this.domainName === null || this.domainName === "null" || this.domainName.length === 0) {
            return this.tokenname || "";
        } else {
            if (this.getTokentype() === TokenType.domainname) {
                return this.tokenname || "";
            } else {
                return `${this.tokenname}@${this.domainName}`;
            }
        }
    }

    public getTokenFullDomainname(): string {
        if (this.domainName === null || this.domainName === "null" || this.domainName.length === 0) {
            return this.tokenname || "";
        } else {
            if (this.getTokentype() === TokenType.domainname) {
                return this.tokenname || "";
            } else {
                return `${this.tokenname}.${this.domainName}`;
            }
        }
    }
    
    public getTokennameDisplay(): string {
        return this.getTokenFullname();
    }

    public isTokenDomainname(): boolean {
        return this.tokentype === TokenType.domainname;  
    }

    public static buildSimpleTokenInfo(
        confirmed: boolean,
        prevblockhash: Sha256Hash | null,
        tokenid: string,
        tokenname: string,
        description: string,
        signnumber: number,
        tokenindex: number,
        amount: BigInteger,
        tokenstop: boolean,
        tokenKeyValues: TokenKeyValues | null,
        revoked: boolean,
        language: string | null,
        classification: string | null,
        tokentype: number,
        decimals: number,
        domainName: string | null,
        domainNameBlockHash: string | null
    ): Token {
        const tokens = new Token();
        tokens.setTokenid(tokenid);
        tokens.setTokenname(tokenname);
        tokens.setDescription(description);
        tokens.tokenstop = tokenstop;
        tokens.tokentype = tokentype;
        tokens.signnumber = signnumber;
        tokens.amount = amount;
        tokens.tokenindex = tokenindex;
        tokens.setConfirmed(confirmed);
        tokens.prevblockhash = prevblockhash;
        tokens.tokenKeyValues = tokenKeyValues;
        tokens.revoked = revoked;
        tokens.language = language;
        tokens.classification = classification;
        tokens.decimals = decimals;
        tokens.domainName = domainName ?? "";
        tokens.domainNameBlockHash = domainNameBlockHash;
        return tokens;
    }

    public static genesisToken(params: NetworkParameters): Token {
        const genesisToken = Token.buildSimpleTokenInfo(true, null, NetworkParameters.BIGTANGLE_TOKENID_STRING,
            NetworkParameters.BIGTANGLE_TOKENNAME, "BigTangle Currency", 1, 0, NetworkParameters.BigtangleCoinTotal,
            true, null, false, null, null, TokenType.currency, NetworkParameters.BIGTANGLE_DECIMAL, null, null);
        const genesisBlock = params.getGenesisBlock();
        if (genesisBlock !== null && genesisBlock !== undefined) {
            genesisToken.setBlockHash(genesisBlock.getHash());
        }
        genesisToken.setTokentype(TokenType.currency);
        return genesisToken;
    }

    public static buildSimpleTokenInfo2(
        confirmed: boolean,
        prevblockhash: Sha256Hash | null,
        tokenid: string,
        tokenname: string,
        description: string,
        signnumber: number,
        tokenindex: number,
        amount: BigInteger,
        tokenstop: boolean,
        decimals: number,
        predecessingDomainBlockHash: string | null
    ): Token {
        return Token.buildSimpleTokenInfo(confirmed, prevblockhash, tokenid, tokenname, description, signnumber,
            tokenindex, amount, tokenstop, null, false, null, null, TokenType.token, decimals, null,
            predecessingDomainBlockHash);
    }

    public static buildDomainnameTokenInfo(
        confirmed: boolean,
        prevblockhash: Sha256Hash | null,
        tokenid: string,
        tokenname: string,
        description: string,
        signnumber: number,
        tokenindex: number,
        tokenstop: boolean,
        domainname: string,
        predecessingDomainBlockHash: string | null
    ): Token {
        return Token.buildSimpleTokenInfo(confirmed, prevblockhash, tokenid, tokenname, description, signnumber,
            tokenindex, BigInteger.ONE, tokenstop, null, false, null, null, TokenType.domainname, 0,
            domainname, predecessingDomainBlockHash);
    }

    constructor(tokenid?: string, tokenname?: string) {
        super();
        if (tokenid) this.tokenid = tokenid;
        if (tokenname) this.tokenname = tokenname;
    }

    public static buildSubtangleTokenInfo(
        confirmed: boolean,
        prevblockhash: Sha256Hash | null,
        tokenid: string,
        tokenname: string,
        description: string,
        domainname: string
    ): Token {
        const tokens = new Token();
        tokens.setTokenid(tokenid);
        tokens.setTokenname(tokenname);
        tokens.setDescription(description);
        tokens.setDomainName(domainname);
        tokens.tokenstop = true;
        tokens.tokentype = TokenType.subtangle;
        tokens.signnumber = 1;
        tokens.amount = BigInteger.ZERO;
        tokens.tokenindex = 1;
        tokens.setConfirmed(confirmed);
        tokens.prevblockhash = prevblockhash;

        return tokens;
    }

    public toString(): string {
        return `Token \n [tokenid=${this.tokenid}, tokenindex=${this.tokenindex}, tokenname=${this.tokenname}` +
               ` \n , description=${this.description}, domainName=${this.domainName}, domainNameBlockHash=${this.domainNameBlockHash}` +
               ` \n , signnumber=${this.signnumber}, tokentype=${this.tokentype}, tokenstop=${this.tokenstop}` +
               `\n , prevblockhash=${this.prevblockhash}, amount=${this.amount}, decimals=${this.decimals}` +
               ` \n , classification=${this.classification}, language=${this.language}, revoked=${this.revoked}]`;
    }
}