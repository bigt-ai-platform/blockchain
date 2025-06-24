import { Wallet } from './wallet/Wallet'; // Corrected import path
import { NetworkParameters } from './NetworkParameters';
import { ECKey } from './ECKey';
import { BigInteger } from 'jsbn';
import { Token } from './Token';
import { TokenInfo } from './TokenInfo';
import { MultiSignAddress } from './MultiSignAddress';
import { Coin } from './Coin';
import { MemoInfo } from './MemoInfo';
import { Sha256Hash } from './Sha256Hash';
import { UTXO } from './UTXO';
import { Block } from './Block'; // Added import for Block
import { TokenKeyValues } from './TokenKeyValues'; // Added import for TokenKeyValues

export abstract class AbstractIntegrationTest {
    protected contextRoot!: string; // Marked as definitely assigned
    protected wallet!: Wallet; // Marked as definitely assigned
    protected testPriv: string = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";
    protected yuanTokenPriv: string = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";
    protected networkParameters!: NetworkParameters; // Marked as definitely assigned

    protected log: { debug: (...args: any[]) => void; warn: (...args: any[]) => void; error: (...args: any[]) => void; };

    constructor() {
        this.log = {
            debug: console.debug,
            warn: console.warn,
            error: console.error,
        };
        // networkParameters will be initialized in the concrete setUp method
    }

    abstract setUp(): Promise<void>;
    abstract close(): Promise<void>;

    protected async createToken(
        key: ECKey,
        tokename: string,
        decimals: number,
        domainname: string,
        description: string,
        amount: BigInteger,
        increment: boolean,
        tokenKeyValues: TokenKeyValues | null, // Changed type
        tokentype: number,
        tokenid: string,
        w: Wallet,
        pubkeyTo?: Uint8Array,
        memoInfo?: MemoInfo
    ): Promise<Block> { // Changed return type
        w.importKey(key);
        const token = Token.buildSimpleTokenInfo(true, Sha256Hash.ZERO_HASH, tokenid, tokename, description, 1, 0,
            amount, !increment, decimals, "", tokenKeyValues); // Pass tokenKeyValues to buildSimpleTokenInfo
        token.setTokentype(tokentype);
        const addresses: MultiSignAddress[] = [];
        addresses.push(new MultiSignAddress(tokenid, "", key.getPublicKeyAsHex()));
        // Explicitly cast to the desired overload to help TypeScript resolve it
        return (w.createToken as (
            key: ECKey,
            domainname: string,
            increment: boolean,
            token: Token,
            addresses: MultiSignAddress[],
            pubkeyTo: Uint8Array,
            memoInfo: MemoInfo
        ) => Promise<Block>)(key, domainname, increment, token, addresses, pubkeyTo || key.getPubKey(), memoInfo || new MemoInfo("coinbase"));
    }

    protected async payBigTo(beneficiary: ECKey, amount: BigInteger, addedBlocks: Block[]): Promise<Block> { // Changed return type and addedBlocks type
        // Simplified implementation
        // In a real scenario, this would involve creating and signing a transaction
        // and adding it to a block.
        this.log.debug(`Paying ${amount.toString()} BIG to ${beneficiary.toAddress(this.networkParameters).toString()}`);
        // Simulate adding a block
        const simulatedBlock = {} as Block; // Dummy Block object
        addedBlocks.push(simulatedBlock);
        return simulatedBlock;
    }

    protected async makeRewardBlock(addedBlocks?: Block[]): Promise<Block> { // Changed return type and addedBlocks type
        // Simplified implementation
        this.log.debug("Making reward block");
        const simulatedRewardBlock = {} as Block; // Dummy Block object
        if (addedBlocks) {
            addedBlocks.push(simulatedRewardBlock);
        }
        return simulatedRewardBlock;
    }

    protected async getBalance(withZero: boolean, key: ECKey): Promise<UTXO[]> {
        // Placeholder implementation
        return [];
    }
}
