import { NetworkParameters } from '../core/NetworkParameters.js';
import { BigInteger } from '../core/BigInteger';

// Add missing imports or declarations
// You may need to adjust the import paths as needed
// import { createGenesis } from '../core/Genesis'; // Uncomment and adjust if createGenesis is exported from another file

// Declare missing constants if not imported
const ID_MAINNET = "org.bigtangle.production";

// Add class-level properties for maxTarget and maxTargetReward
let maxTarget: BigInteger;
let maxTargetReward: BigInteger;

 
export class MainNetParams extends NetworkParameters {
 
    public id: string;
    public addressHeader: number;
    public p2shHeader: number;
    public dumpedPrivateKeyHeader: number;
    public acceptableAddressCodes: number[];
   

    constructor() {
        super();
        // !!!this is initial value and used in genesis block hash, it can be
        // changed only for height
        maxTarget = new BigInteger("578960377169117509212217050695880916496095398817113098493422368414323410");
        // !!!this is initial value and used in genesis block hash, it can be
        // changed only for height
        maxTargetReward = new BigInteger("5789603771691175092122170506958809164960953988171130984934223684143236");

        this.dumpedPrivateKeyHeader = 128;
        this.addressHeader = 0;
        this.p2shHeader = 5;
        this.acceptableAddressCodes = [this.addressHeader, this.p2shHeader];

        this.packetMagic = 0xf9beb4d9;
        this.bip32HeaderPub = 0x0488B21E; // The 4 byte header that serializes in
                                          // base58 to "xpub".
        this.bip32HeaderPriv = 0x0488ADE4; // The 4 byte header that serializes in
                                           // base58 to "xprv"

        this.genesisPub = "03d6053241c5abca6621c238922e7473977320ef310be0a8538cc2df7ee5a0187c";

        this.permissionDomainname = ["0222c35110844bf00afd9b7f08788d79ef6edc0dce19be6182b44e07501e637a58"];
     
       
        this.id = ID_MAINNET;
        this.subsidyDecreaseBlockCount = 210000;
        this.spendableCoinbaseDepth = 100;

        this.dnsSeeds = [];
    

        this.addrSeeds = [];
 
        // seeds for servers

    
    }

    public getAddressHeader(): number {
        return this.addressHeader;
    }

    public getP2SHHeader(): number {
        return this.p2shHeader;
    }

    public getDumpedPrivateKeyHeader(): number {
        return this.dumpedPrivateKeyHeader;
    }

    public getAcceptableAddressCodes(): number[] {
        return this.acceptableAddressCodes;
    }

    public getUriScheme(): string {
        return "bigtangle";
    }

   
    public serverSeeds(): string[] {
        // Return an array of server seeds; adjust as needed
        return [];
    }

    public getOrderPriceShift(orderBaseTokens: string): number {
        // Return a default value; adjust as needed
        return 0;
    }

    public static get(): MainNetParams {
        return new MainNetParams();
    }
}
