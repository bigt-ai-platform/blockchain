// TypeScript translation of Wallet.java
// Imports from core, utils, exception, params, script as requested
import { Address } from '../core/Address';
import { Block } from '../core/Block';
import { Coin } from '../core/Coin';
import { ContractEventCancelInfo } from '../core/ContractEventCancelInfo';
import { ContractEventInfo } from '../core/ContractEventInfo';
import { DataClassName } from '../core/DataClassName';
import { ECKey } from '../core/ECKey';
import { KeyValue } from '../core/KeyValue';
import { MemoInfo } from '../core/MemoInfo';
import { MultiSign } from '../core/MultiSign';
import { MultiSignAddress } from '../core/MultiSignAddress';
import { MultiSignBy } from '../core/MultiSignBy';
import { NetworkParameters } from '../core/NetworkParameters';
import { OrderCancelInfo } from '../core/OrderCancelInfo';
import { OrderOpenInfo } from '../core/OrderOpenInfo';
import { Sha256Hash } from '../core/Sha256Hash';
import { Side } from '../core/Side';
import { Token } from '../core/Token';
import { TokenInfo } from '../core/TokenInfo';
import { Transaction } from '../core/Transaction';
import { TransactionInput } from '../core/TransactionInput';
import { TransactionOutput } from '../core/TransactionOutput';
import { UTXO } from '../core/UTXO';
import { UserSettingDataInfo } from '../core/UserSettingDataInfo';
import { Utils } from '../utils/Utils';
import { DeterministicKey } from '../crypto/DeterministicKey';
import { ECIESCoder } from '../crypto/ECIESCoder';
import { TransactionSignature } from '../crypto/TransactionSignature';
import { InsufficientMoneyException } from '../exception/InsufficientMoneyException';
import { NoDataException } from '../exception/NoDataException';
import { NoTokenException } from '../exception/NoTokenException';
import { ReqCmd } from '../params/ReqCmd';
import { ServerPool } from '../pool/server/ServerPool';
import { GetDomainTokenResponse } from '../response/GetDomainTokenResponse';
import { GetOutputsResponse } from '../response/GetOutputsResponse';
import { GetTokensResponse } from '../response/GetTokensResponse';
import { MultiSignByRequest } from '../response/MultiSignByRequest';
import { MultiSignResponse } from '../response/MultiSignResponse';
import { OrderTickerResponse } from '../response/OrderTickerResponse';
import { OutputsDetailsResponse } from '../response/OutputsDetailsResponse';
import { PermissionedAddressesResponse } from '../response/PermissionedAddressesResponse';
import { TokenIndexResponse } from '../response/TokenIndexResponse';
import { Script } from '../script/Script';
import { ScriptBuilder } from '../script/ScriptBuilder';
import { Json } from '../utils/Json';
import { MonetaryFormat } from '../utils/MonetaryFormat';
import { OkHttp3Util } from '../utils/OkHttp3Util';
import { WalletBase } from './WalletBase';
import { KeyChainGroup } from './KeyChainGroup';
import { LocalTransactionSigner } from '../signers/LocalTransactionSigner';

// TODO: Implement Wallet class translation from Java to TypeScript here, using the above imports and adapting Java idioms to TypeScript.

export class Wallet extends WalletBase {
    private static readonly log = console; // Replace with a logger if needed
    keyChainGroup: KeyChainGroup;
    url: string | null = null;

    // Static method: fromKeys
    static fromKeys(params: NetworkParameters, keys: ECKey[]): Wallet {
        for (const key of keys) {
            if (key instanceof DeterministicKey) {
                throw new Error('DeterministicKey not allowed');
            }
        }
        const group = new KeyChainGroup(params);
        group.importKeys(keys);
        return new Wallet(params, group);
    }

    constructor(params: NetworkParameters, keyChainGroup?: KeyChainGroup, url?: string | null) {
        super();
        this.params = params;
        this.keyChainGroup = keyChainGroup ?? new KeyChainGroup(params);
        if ((params as any).getId && (NetworkParameters as any).ID_UNITTESTNET && (params as any).getId() === (NetworkParameters as any).ID_UNITTESTNET) {
            this.keyChainGroup.setLookaheadSize(5);
        }
        if (this.keyChainGroup.numKeys() === 0) {
            this.keyChainGroup.createAndActivateNewHDChain();
        }
        this.signers = [];
        this.addTransactionSigner(new LocalTransactionSigner());
        if (!url) {
            this.serverPool = new ServerPool(params) as ServerPool;
        } else {
            // If setServerURL exists, call it, otherwise just set url
            if (typeof (this as any).setServerURL === 'function') {
                (this as any).setServerURL(url);
            } else {
                this.url = url;
            }
        }
    }

    // TODO: Implement all methods from Java Wallet class here, adapting to TypeScript.
    // Stubs for all methods (to be filled in next steps):

    static fromKeysSingle(params: NetworkParameters, key: ECKey): Wallet { throw new Error('Not implemented'); }
    static fromKeysWithUrl(params: NetworkParameters, key: ECKey, url: string): Wallet { throw new Error('Not implemented'); }
       async getTip(): Promise<Block> { 
    return this.params.getDefaultSerializer().makeBlock(await this.getTipData());
    }

    async getTipData(): Promise<Uint8Array> {
        const requestParam = {};
        // Use OkHttp3Util to POST and get block data
        return await OkHttp3Util.postAndGetBlock(
            this.getServerURL() + ReqCmd.getTip,
            Json.jsonmapper().stringify(requestParam)
        );
    }
    
    saveToFileStream(f: any): void {
        // Assuming WalletProtobufSerializer is implemented in TypeScript
        // and this.lock is a mutex or similar if needed
        // This is a synchronous method in Java, but you may want to adapt to async if your serializer is async
        // For now, we assume synchronous
        // TODO: Implement locking if needed
        const { WalletProtobufSerializer } = require('../utils/WalletProtobufSerializer');
        new WalletProtobufSerializer().writeWallet(this, f);
    }

    async calculateAllSpendCandidates(aesKey: any, multisigns: boolean): Promise<any[]> {
        const { FreeStandingTransactionOutput } = require('../core/FreeStandingTransactionOutput');
        const candidates: any[] = [];
        for (const output of await this.calculateAllSpendCandidatesUTXO(aesKey, multisigns)) {
            candidates.push(new FreeStandingTransactionOutput(this.params, output));
        }
        return candidates;
    }

    checkSpendpending(output: UTXO): boolean {
        const SPENTPENDINGTIMEOUT = 5 * 60 * 1000;
        if (output.isSpendPending()) {
            return (Date.now() - output.getSpendPendingTime()) > SPENTPENDINGTIMEOUT;
        }
        return true;
    }

    async calculateAllSpendCandidatesUTXO(aesKey: any, multisigns: boolean): Promise<UTXO[]> {
        const candidates: UTXO[] = [];
        const pubKeyHashs: string[] = [];
        for (const ecKey of this.walletKeys(aesKey)) {
            pubKeyHashs.push(Utils.HEX.encode(ecKey.getPubKeyHash()));
        }
        // OkHttp3Util.post expects (urls: string[], data: Buffer)
        const response = await OkHttp3Util.post(
            [this.getServerURL() + String(ReqCmd.getOutputs)],
            Buffer.from(JSON.stringify(pubKeyHashs))
        );
        const responseStr = typeof response === 'string' ? response : response.toString();
        const getOutputsResponse: GetOutputsResponse = Json.jsonmapper().parse(responseStr);
        const outputs = getOutputsResponse && typeof getOutputsResponse.getOutputs === 'function' ? getOutputsResponse.getOutputs() : [];
        if (outputs) {
            for (const output of outputs) {
                if (this.checkSpendpending(output)) {
                    if (multisigns) {
                        candidates.push(output);
                    } else if (!output.isMultiSig || !output.isMultiSig()) {
                        candidates.push(output);
                    }
                }
            }
        }
        // Shuffle candidates
        for (let i = candidates.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [candidates[i], candidates[j]] = [candidates[j], candidates[i]];
        }
        return candidates;
    }

    async saveToken(tokenInfo: TokenInfo, basecoin: Coin, ownerKey: ECKey, aesKey: any, pubKeyTo?: Uint8Array, memoInfo?: MemoInfo): Promise<Block> {
        const token = tokenInfo.getToken?.();
        if (!token) throw new Error('TokenInfo.getToken() returned null or undefined');
        // Domain name logic
        const domainNameBlockHash = token.getDomainNameBlockHash?.();
        const tokenObj = tokenInfo.getToken && tokenInfo.getToken();
        const domainName = tokenObj ? tokenObj.getDomainName?.() : undefined;
        if (Utils.isBlank(domainNameBlockHash) && Utils.isBlank(domainName)) {
            const domainname = token.getDomainName?.();
            const getDomainBlockHashResponse = await this.getDomainNameBlockHash(domainname);
            const domainNameToken = getDomainBlockHashResponse.getdomainNameToken?.();
            if (domainNameToken) {
                token.setDomainNameBlockHash?.(domainNameToken.getBlockHashHex?.() ?? null);
                token.setDomainName?.(domainNameToken.getTokenname?.() ?? '');
            }
        }
        if (Utils.isBlank(token.getDomainNameBlockHash?.()) && !Utils.isBlank(domainName)) {
            const domainResp = await this.getDomainNameBlockHash(domainName ?? '');
            const domain = domainResp.getdomainNameToken?.();
            if (domain) {
                token.setDomainNameBlockHash?.(domain.getBlockHashHex?.() ?? null);
            }
        }
        const multiSignAddresses = tokenInfo.getMultiSignAddresses?.() ?? [];
        const permissionedAddressesResponse = await this.getPrevTokenMultiSignAddressList(token);
        const permMultiSignAddresses = permissionedAddressesResponse?.getMultiSignAddresses?.();
        if (permMultiSignAddresses && permMultiSignAddresses.length > 0) {
            if (Utils.isBlank(token.getDomainName?.())) {
                token.setDomainName?.(permissionedAddressesResponse.getDomainName?.() ?? '');
            }
            for (const multiSignAddress of permMultiSignAddresses) {
                const pubKeyHex = multiSignAddress.getPubKeyHex?.() ?? '';
                const tokenid = token.getTokenid?.() ?? '';
                multiSignAddresses.push(new MultiSignAddress(tokenid, '', pubKeyHex, 0));
            }
        }
        token.setSignnumber?.((token.getSignnumber?.() ?? 0) + 1);
        const block = await this.getTip();
        // TODO: Adapt Block.Type.BLOCKTYPE_TOKEN_CREATION to your TypeScript Block implementation
        if (block.setBlockType) block.setBlockType((Block as any).Type?.BLOCKTYPE_TOKEN_CREATION ?? 0);
        if (block.addCoinbaseTransaction) block.addCoinbaseTransaction(pubKeyTo ?? (ownerKey as any).getPubKey?.(), basecoin, tokenInfo, memoInfo ?? new MemoInfo('coinbase'));
        const transaction = block.getTransactions?.()?.[0];
        const sighash = transaction?.getHash?.();
        // TODO: Adapt sign and encodeDER to your ECKey and ECDSASignature implementations
        if (sighash && ownerKey.sign) {
            const party1Signature = ownerKey.sign(sighash as any, aesKey);
            if (party1Signature && typeof party1Signature.encodeDER === 'function') {
                // party1Signature.encodeDER(); // Use as needed
            }
        }
        // TODO: Add signature to transaction as needed
        return block;
    }

    chopped<T>(list: T[], L: number): T[][] {
        // Splits the list into sublists of length L (last may be shorter)
        const result: T[][] = [];
        for (let i = 0; i < list.length; i += L) {
            result.push(list.slice(i, i + L));
        }
        return result;
    }

    /**
     * Create a fee transaction block.
     * @param basecoin The coin to use for the fee.
     * @param ownerKey The ECKey of the owner paying the fee.
     * @param aesKey The AES key for decrypting private keys (if needed).
     * @param pubKeyTo The public key to send the fee to (optional).
     * @param memoInfo Optional memo information.
     * @returns A Promise resolving to the created Block.
     */
    async feeTransaction(
        basecoin: Coin,
        ownerKey: ECKey,
        aesKey: any,
        pubKeyTo?: Uint8Array,
        memoInfo?: MemoInfo
    ): Promise<Block> {
        const block = await this.getTip();
        if (block.setBlockType) {
            block.setBlockType((Block as any).Type?.BLOCKTYPE_FEE ?? 0);
        }
        // Create a minimal Token for fee transaction
        const dummyToken: any = new (require('../core/Token').Token)();
        if (dummyToken.setTokenid) dummyToken.setTokenid('fee');
        const dummyTokenInfo = new TokenInfo();
        if (dummyTokenInfo.setToken && typeof dummyTokenInfo.setToken === 'function') {
            dummyTokenInfo.setToken(dummyToken);
        }
        if (block.addCoinbaseTransaction) {
            block.addCoinbaseTransaction(
                pubKeyTo ?? (ownerKey as any).getPubKey?.(),
                basecoin,
                dummyTokenInfo,
                memoInfo ?? new MemoInfo('fee')
            );
        }
        // Get the transaction and sign it
        const transaction = block.getTransactions?.()?.[0];
        const sighash = transaction?.getHash?.();
        if (sighash && ownerKey.sign) {
            const signature = ownerKey.sign(sighash as any, aesKey);
            if (signature && typeof signature.encodeDER === 'function') {
                // Optionally use signature.encodeDER() if needed
            }
            // Attach signature to transaction if required by your model
            if (transaction && Array.isArray((transaction as any).signatures)) {
                (transaction as any).signatures.push(signature);
            } else if (transaction && (transaction as any).setSignature) {
                (transaction as any).setSignature(signature);
            }
        }
        return block;
    }

    /**
     * Retrieves the previous token's multi-sign address list from the server.
     * @param token The Token whose multi-sign addresses are to be fetched.
     * @returns A Promise resolving to a PermissionedAddressesResponse.
     */
    async getPrevTokenMultiSignAddressList(token: Token): Promise<PermissionedAddressesResponse> {
        const tokenid = token.getTokenid?.() ?? '';
        const url = this.getServerURL() +  ReqCmd.getPayMultiSignAddressList;
        const response = await OkHttp3Util.post([url], Buffer.from(JSON.stringify([tokenid])));
        const responseStr = typeof response === 'string' ? response : response.toString();
        return Json.jsonmapper().parse(responseStr) as PermissionedAddressesResponse;
    }

    /**
     * Retrieves the domain name block hash for a given domain name from the server.
     * @param domainName The domain name to query.
     * @returns A Promise resolving to a GetDomainTokenResponse.
     */
    async getDomainNameBlockHash(domainName: string): Promise<GetDomainTokenResponse> {
        const url = this.getServerURL() + (ReqCmd.getDomainNameBlockHash || '/getDomainNameBlockHash');
        const response = await OkHttp3Util.post([url], Buffer.from(JSON.stringify([domainName])));
        const responseStr = typeof response === 'string' ? response : response.toString();
        return Json.jsonmapper().parse(responseStr);
    }

    /**
     * Submits a multi-signature for a transaction to the server.
     * @param multiSignBy The MultiSignBy object containing signature info.
     * @returns A Promise resolving to a MultiSignResponse.
     */
    async multiSign(multiSignBy: MultiSignBy): Promise<MultiSignResponse> {
        const url = this.getServerURL() + '/multiSign';
        const requestBody = Json.jsonmapper().stringify(multiSignBy);
        const response = await OkHttp3Util.post([url], Buffer.from(requestBody));
        const responseStr = typeof response === 'string' ? response : response.toString();
        return Json.jsonmapper().parse(responseStr);
    }

    /**
     * Submits a multi-signature for a token using the tokenid and ECKey.
     * @param tokenid The token ID to sign.
     * @param outKey The ECKey to use for signing.
     * @param aesKey The AES key for signing (if needed).
     * @returns A Promise resolving to a MultiSignResponse.
     */
    async multiSignToken(tokenid: string, outKey: ECKey, aesKey: any): Promise<MultiSignResponse> {
        // Prepare the MultiSignBy object
        const pubKeyHex = Utils.HEX.encode(outKey.getPubKeyBytes());
        // The signature is typically over the tokenid (or a hash of it)
        const sighash = require('crypto').createHash('sha256').update(tokenid).digest();
        const signatureObj = outKey.sign(sighash, aesKey);
        // Convert signature to DER hex string if needed
        let signature = '';
        if (signatureObj && typeof signatureObj.encodeDER === 'function') {
            signature = Buffer.from(signatureObj.encodeDER()).toString('hex');
        } else if (typeof signatureObj === 'string') {
            signature = signatureObj;
        }
        const multiSignBy = new MultiSignBy();
        if (multiSignBy.setTokenid) multiSignBy.setTokenid(tokenid);
        if (multiSignBy.setSignature) multiSignBy.setSignature(signature);
        // Call the main multiSign method
        return this.multiSign(multiSignBy);
    }

    /**
     * Calculates the result of a mathematical expression or operation.
     * This is a placeholder for the Java Wallet.calc method. Adapt as needed for your use case.
     * @param expr The expression or operands to calculate.
     * @returns The result of the calculation.
     */
    calc(expr: string | number | any): number {
        // If expr is a string, try to evaluate as a simple math expression (safe subset)
        if (typeof expr === 'string') {
            // Only allow numbers and basic operators for safety
            if (/^[0-9+\-*/ ().]+$/.test(expr)) {
                // eslint-disable-next-line no-eval
                return Function(`'use strict'; return (${expr})`)();
            } else {
                throw new Error('Unsafe or invalid expression');
            }
        }
        // If expr is a number, return as is
        if (typeof expr === 'number') {
            return expr;
        }
        // If expr is an object with a value property, return that
        if (expr && typeof expr.value === 'number') {
            return expr.value;
        }
        throw new Error('Unsupported expression type for calc');
    }

    /**
     * Retrieves the calculated token index from the server for a given tokenid.
     * @param tokenid The token ID to query.
     * @returns A Promise resolving to a TokenIndexResponse.
     */
    async getServerCalTokenIndex(tokenid: string): Promise<TokenIndexResponse> {
        const url = this.getServerURL() + '/getServerCalTokenIndex';
        const response = await OkHttp3Util.post([url], Buffer.from(JSON.stringify([tokenid])));
        const responseStr = typeof response === 'string' ? response : response.toString();
        return Json.jsonmapper().parse(responseStr);
    }

    /**
     * Checks if the given block is a prototype block (not yet finalized or valid for broadcast).
     * @param block The Block to check.
     * @returns True if the block is a prototype, false otherwise.
     */
    checkBlockPrototype(block: Block): boolean {
        // This logic should match the Java Wallet.checkBlockPrototype method.
        // Typically, a prototype block may have a special type or missing required fields.
        // Here, we check for a property or type that marks it as a prototype.
        // Adapt this logic to your actual Block implementation as needed.
        if (!block) return false;
        // Example: check for a prototype type or missing hash/height
        if (typeof (block as any).isPrototype === 'function') {
            return (block as any).isPrototype();
        }
        // Fallback: check for missing hash or height
        if (!('hash' in block) || !('height' in block)) {
            return true;
        }
        // If block type is explicitly marked as prototype
        if ((block as any).getBlockType && (block as any).getBlockType() === (Block as any).Type?.BLOCKTYPE_PROTOTYPE) {
            return true;
        }
        return false;
    }

    /**
     * Requests the server to create a new token.
     * @param tokenInfo The TokenInfo object describing the token to create.
     * @param basecoin The Coin to use as the base for the token.
     * @param ownerKey The ECKey of the owner/creator.
     * @param aesKey The AES key for signing (if needed).
     * @param pubKeyTo Optional public key to assign the token to.
     * @param memoInfo Optional memo information.
     * @returns A Promise resolving to the created Block.
     */
    async createToken(
        tokenInfo: TokenInfo,
        basecoin: Coin,
        ownerKey: ECKey,
        aesKey: any,
        pubKeyTo?: Uint8Array,
        memoInfo?: MemoInfo
    ): Promise<Block> {
        // This method is typically a wrapper for saveToken, but can include extra logic if needed
        return this.saveToken(tokenInfo, basecoin, ownerKey, aesKey, pubKeyTo, memoInfo);
    }

    /**
     * Publishes a domain name to the server, associating it with a token.
     * @param tokenInfo The TokenInfo object containing the domain name and token data.
     * @param basecoin The Coin to use for the transaction.
     * @param ownerKey The ECKey of the owner/creator.
     * @param aesKey The AES key for signing (if needed).
     * @param pubKeyTo Optional public key to assign the domain to.
     * @param memoInfo Optional memo information.
     * @returns A Promise resolving to the created Block.
     */
    async publishDomainName(
        tokenInfo: TokenInfo,
        basecoin: Coin,
        ownerKey: ECKey,
        aesKey: any,
        pubKeyTo?: Uint8Array,
        memoInfo?: MemoInfo
    ): Promise<Block> {
        // This is typically a wrapper for saveToken, but can include extra logic for domain publishing if needed
        return this.saveToken(tokenInfo, basecoin, ownerKey, aesKey, pubKeyTo, memoInfo);
    }

    /**
     * Retrieves user setting data info from the server for the given address.
     * @param address The address to query user setting data for.
     * @returns A Promise resolving to a UserSettingDataInfo object.
     */
    async getUserSettingDataInfo(address: string): Promise<UserSettingDataInfo> {
        const url = this.getServerURL() + '/getUserSettingDataInfo';
        const response = await OkHttp3Util.post([url], Buffer.from(JSON.stringify([address])));
        const responseStr = typeof response === 'string' ? response : response.toString();
        return Json.jsonmapper().parse(responseStr);
    }

    /**
     * Saves user data info to the server for the given address.
     * @param address The address to associate with the user data.
     * @param userSettingDataInfo The UserSettingDataInfo object to save.
     * @returns A Promise resolving to the server response (could be a status or updated UserSettingDataInfo).
     */
    async saveUserdata(address: string, userSettingDataInfo: UserSettingDataInfo): Promise<any> {
        const url = this.getServerURL() + '/saveUserdata';
        const payload = { address, userSettingDataInfo };
        const response = await OkHttp3Util.post([url], Buffer.from(JSON.stringify(payload)));
        const responseStr = typeof response === 'string' ? response : response.toString();
        return Json.jsonmapper().parse(responseStr);
    }

    /**
     * Pay all small coins in a wallet to one destination. This destination can be in the same wallet.
     * @param aesKey The AES key for decrypting private keys (if needed).
     * @param destination The address to send all coins to.
     * @param tokenid The token id as a Buffer or Uint8Array.
     * @param memo Memo string for the transaction.
     * @param low Optional minimum value for UTXOs to include (as a BigInt or string/number convertible to BigInt).
     * @returns A Promise resolving to a list of Blocks (usually one block).
     */
    async payPartsToOne(
        aesKey: any,
        destination: string,
        tokenid: Uint8Array,
        memo: string,
        low: bigint | number | string = 0n
    ): Promise<Block[]> {
        const tokenidBuf = Buffer.from(tokenid);
        const utxos: UTXO[] = await this.calculateAllSpendCandidatesUTXO(aesKey, false);
        let sum = Coin.valueOf(0n, tokenidBuf);
        let size = 0;
        const maxSize = (NetworkParameters as any).MAX_DEFAULT_BLOCK_SIZE
            ? (NetworkParameters as any).MAX_DEFAULT_BLOCK_SIZE / 10000
            : 1000; // fallback
        const lowBigInt = typeof low === 'bigint' ? low : BigInt(low);
        for (const u of utxos) {
            const valueObj = u.getValue?.();
            if (!valueObj) continue;
            const utxoTokenId = valueObj.getTokenid?.();
            // Compare tokenid (assume Buffer or Uint8Array)
            let sameToken = false;
            if (utxoTokenId && utxoTokenId.length === tokenid.length) {
                sameToken = true;
                for (let i = 0; i < utxoTokenId.length; i++) {
                    if (utxoTokenId[i] !== tokenid[i]) {
                        sameToken = false;
                        break;
                    }
                }
            }
            if (sameToken && size < maxSize) {
                const value = valueObj.getValue?.();
                if (value == null) continue;
                const valueBigInt = typeof value === 'bigint' ? value : BigInt(value);
                if (lowBigInt === 0n || valueBigInt > lowBigInt) {
                    sum = sum.add(valueObj);
                    size += 1;
                }
            }
        }
        // Subtract fee if needed
        let isSameToken = false;
        if (NetworkParameters.BIGTANGLE_TOKENNAME && tokenid) {
            if (typeof NetworkParameters.BIGTANGLE_TOKENNAME === 'string') {
                const tokenNameBuf = Buffer.from(NetworkParameters.BIGTANGLE_TOKENNAME, 'utf8');
                isSameToken =
                    tokenNameBuf.length === tokenid.length &&
                    tokenNameBuf.every((v: number, i: number) => v === tokenid[i]);
            } else if (
                (Array.isArray(NetworkParameters.BIGTANGLE_TOKENNAME) ||
                    (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView(NetworkParameters.BIGTANGLE_TOKENNAME))) &&
                typeof (NetworkParameters.BIGTANGLE_TOKENNAME as any).length === 'number'
            ) {
                const arr = NetworkParameters.BIGTANGLE_TOKENNAME as any;
                isSameToken =
                    arr.length === tokenid.length &&
                    Array.prototype.every.call(arr, (v: number, i: number) => v === tokenid[i]);
            }
        }
        if (
            typeof this.getFee === 'function' &&
            this.getFee() &&
            isSameToken
        ) {
            sum = sum.subtract((Coin as any).FEE_DEFAULT ?? Coin.valueOf(1n, tokenidBuf));
        }
        // Call pay (should return a list of blocks, but Java returns List<Block> with one block)
        if (typeof (this as any).pay !== 'function') {
            throw new Error('pay method not implemented in Wallet');
        }
        const block = await (this as any).pay(aesKey, destination, sum, new MemoInfo(memo));
        return [block];
    }

    /**
     * Pay the given amount to the destination address, using available UTXOs.
     * @param aesKey The AES key for decrypting private keys (if needed).
     * @param destination The destination address as a string.
     * @param amount The Coin amount to send.
     * @param memoInfo Optional MemoInfo for the transaction.
     * @returns A Promise resolving to the created Block.
     */
    async pay(
        aesKey: any,
        destination: string,
        amount: Coin,
        memoInfo?: MemoInfo
    ): Promise<Block> {
        // Gather spendable UTXOs
        const candidates = await this.calculateAllSpendCandidates(aesKey, false);
        // Find the destination Address object
        const AddressClass = require('../core/Address').Address;
        const destAddr = AddressClass.fromString
            ? AddressClass.fromString(destination)
            : new AddressClass(destination);
        // Create the transaction
        const tx = (this as any).createTransaction(aesKey, candidates, destAddr, amount, memoInfo?.toString?.() ?? (memoInfo as any)?.memo ?? '');
        // Create a block with this transaction
        const block = await (this as any).payTransaction([tx]);
        // Optionally solve and post the block
        // return await this.solveAndPost(block);
        return block;
    }

    /**
     * Pay the given amount to the destination address, using available UTXOs, and post to a subtangle server.
     * @param aesKey The AES key for decrypting private keys (if needed).
     * @param destination The destination address as a string.
     * @param amount The Coin amount to send.
     * @param memoInfo Optional MemoInfo for the transaction.
     * @param subtangleUrl The URL of the subtangle server to post to.
     * @returns A Promise resolving to the posted Block.
     */
    async paySubtangle(
        aesKey: any,
        destination: string,
        amount: Coin,
        memoInfo: MemoInfo | undefined,
        subtangleUrl: string
    ): Promise<Block> {
        // Gather spendable UTXOs
        const candidates = await this.calculateAllSpendCandidates(aesKey, false);
        // Find the destination Address object
        const AddressClass = require('../core/Address').Address;
        const destAddr = AddressClass.fromString
            ? AddressClass.fromString(destination)
            : new AddressClass(destination);
        // Create the transaction
        const tx = (this as any).createTransaction(aesKey, candidates, destAddr, amount, memoInfo?.toString?.() ?? (memoInfo as any)?.memo ?? '');
        // Create a block with this transaction
        const block = await (this as any).payTransaction([tx]);
        // Post the block to the subtangle server
        const url = subtangleUrl + (ReqCmd.saveBlock || '/saveBlock');
        const blockBytes = block.bitcoinSerialize ? block.bitcoinSerialize() : Buffer.from([]);
        const response = await OkHttp3Util.post([url], blockBytes);
        // Optionally, parse the response as a Block if needed
        if (response && this.params?.getDefaultSerializer) {
            return this.params.getDefaultSerializer().makeBlock(response);
        }
        return block;
    }

    /**
     * Pay the given amount to the destination address as a contract payment, using available UTXOs.
     * @param aesKey The AES key for decrypting private keys (if needed).
     * @param destination The destination address as a string.
     * @param amount The Coin amount to send.
     * @param memoInfo Optional MemoInfo for the transaction.
     * @param contractInfo Optional contract information to attach to the transaction (object or string).
     * @returns A Promise resolving to the created Block.
     */
    async payContract(
        aesKey: any,
        destination: string,
        amount: Coin,
        memoInfo?: MemoInfo,
        contractInfo?: any
    ): Promise<Block> {
        // Gather spendable UTXOs
        const candidates = await this.calculateAllSpendCandidates(aesKey, false);
        // Find the destination Address object
        const AddressClass = require('../core/Address').Address;
        const destAddr = AddressClass.fromString
            ? AddressClass.fromString(destination)
            : new AddressClass(destination);
        // Create the transaction
        let memo = memoInfo?.toString?.() ?? (memoInfo as any)?.memo ?? '';
        // Attach contract info to memo if provided
        if (contractInfo) {
            if (typeof contractInfo === 'string') {
                memo += `|contract:${contractInfo}`;
            } else {
                memo += `|contract:${JSON.stringify(contractInfo)}`;
            }
        }
        const tx = (this as any).createTransaction(aesKey, candidates, destAddr, amount, memo);
        // Create a block with this transaction
        const block = await (this as any).payTransaction([tx]);
        // Optionally solve and post the block
        // return await this.solveAndPost(block);
        return block;
    }

    /**
     * Cancel a contract event by posting a cancel transaction to the blockchain.
     * @param aesKey The AES key for decrypting private keys (if needed).
     * @param destination The destination address as a string.
     * @param amount The Coin amount to send (usually zero for cancel).
     * @param memoInfo Optional MemoInfo for the transaction.
     * @param contractCancelInfo The contract event cancel info object (should be serializable).
     * @returns A Promise resolving to the created Block.
     */
    async contractEventCancel(
        aesKey: any,
        destination: string,
        amount: Coin,
        memoInfo: MemoInfo | undefined,
        contractCancelInfo: any
    ): Promise<Block> {
        // Gather spendable UTXOs
        const candidates = await this.calculateAllSpendCandidates(aesKey, false);
        // Find the destination Address object
        const AddressClass = require('../core/Address').Address;
        const destAddr = AddressClass.fromString
            ? AddressClass.fromString(destination)
            : new AddressClass(destination);
        // Create the transaction
        let memo = memoInfo?.toString?.() ?? (memoInfo as any)?.memo ?? '';
        // Attach contract cancel info to memo
        if (contractCancelInfo) {
            memo += `|contractCancel:${JSON.stringify(contractCancelInfo)}`;
        }
        const tx = (this as any).createTransaction(aesKey, candidates, destAddr, amount, memo);
        // Create a block with this transaction
        const block = await (this as any).payTransaction([tx]);
        // Optionally solve and post the block
        // return await this.solveAndPost(block);
        return block;
    }

    /**
     * Create a transaction from a provided list of spend candidates (UTXOs) to a destination address for a given amount and memo, without splitting outputs.
     * @param aesKey The AES key for decrypting private keys (if needed).
     * @param destination The destination address as a string.
     * @param amount The Coin amount to send.
     * @param memoInfo MemoInfo for the transaction.
     * @param candidates List of FreeStandingTransactionOutput (UTXO wrappers).
     * @returns A Transaction object.
     */
    payFromListNoSplitTransaction(
        aesKey: any,
        destination: string,
        amount: Coin,
        memoInfo: MemoInfo,
        candidates: any[]
    ): Transaction {
        // Find the destination Address object
        const AddressClass = require('../core/Address').Address;
        const destAddr = AddressClass.fromString
            ? AddressClass.fromString(destination)
            : new AddressClass(destination);
        // Prepare transaction inputs and outputs
        const TransactionClass = require('../core/Transaction').Transaction;
        const TransactionInputClass = require('../core/TransactionInput').TransactionInput;
        const TransactionOutputClass = require('../core/TransactionOutput').TransactionOutput;
        let totalInput = Coin.valueOf(0n, amount.getTokenid());
        const inputs = [];
        for (const candidate of candidates) {
            // candidate is a FreeStandingTransactionOutput or similar
            const utxo = candidate.utxo || candidate.output || candidate;
            const value = utxo.getValue?.();
            if (!value) continue;
            totalInput = totalInput.add(value);
            const input = new TransactionInputClass(utxo.getHash?.(), utxo.getIndex?.());
            inputs.push(input);
            if (totalInput.isGreaterThan(amount)) break;
        }
        if (!(totalInput.isGreaterThan(amount) || totalInput.equals(amount))) {
            throw new Error('Insufficient funds for transaction');
        }
        // Outputs: main destination
        const outputs = [
            new TransactionOutputClass(amount, destAddr)
        ];
        // Change output if needed
        const change = totalInput.subtract(amount);
        if (change.isGreaterThan(Coin.valueOf(0n, amount.getTokenid()))) {
            // Send change back to a wallet address
            const changeKey = this.walletKeys(aesKey)[0];
            const changeAddr = AddressClass.fromKey
                ? AddressClass.fromKey(this.params, changeKey)
                : new AddressClass(changeKey.getPubKeyHash());
            outputs.push(new TransactionOutputClass(change, changeAddr));
        }
        // Create the transaction
        const tx = new TransactionClass(inputs, outputs, memoInfo);
        // Optionally sign the transaction here if needed
        // ...
        return tx;
    }

    /**
     * Create a block by adding a transaction (from a provided list of spend candidates) to a tip block, without splitting outputs.
     * @param aesKey The AES key for decrypting private keys (if needed).
     * @param destination The destination address as a string.
     * @param amount The Coin amount to send.
     * @param memoInfo MemoInfo for the transaction.
     * @param candidates List of FreeStandingTransactionOutput (UTXO wrappers).
     * @param tipBlock The tip Block to add the transaction to.
     * @returns The updated Block with the new transaction.
     */
    payFromListNoSplit(
        aesKey: any,
        destination: string,
        amount: Coin,
        memoInfo: MemoInfo,
        candidates: any[],
        tipBlock: Block
    ): Block {
        const tx = this.payFromListNoSplitTransaction(aesKey, destination, amount, memoInfo, candidates);
        if (typeof tipBlock.addTransaction === 'function') {
            tipBlock.addTransaction(tx);
        } else if (Array.isArray((tipBlock as any).transactions)) {
            (tipBlock as any).transactions.push(tx);
        }
        return tipBlock;
    }

    /**
     * Pay from a list of UTXOs, splitting into multiple blocks if needed.
     * @param aesKey The AES key for decrypting private keys (if needed).
     * @param destination The destination address as a string.
     * @param amount The Coin amount to send.
     * @param memoInfo MemoInfo for the transaction.
     * @param coinList List of FreeStandingTransactionOutput (UTXO wrappers).
     * @param split The maximum number of UTXOs per block.
     * @returns A Promise resolving to a list of Blocks.
     */
    async payFromList(
        aesKey: any,
        destination: string,
        amount: Coin,
        memoInfo: MemoInfo,
        coinList: any[],
        split: number
    ): Promise<Block[]> {
        // Filter coinList by token id
        const tokenid = amount.getTokenid();
        const coinTokenList = (this as any).filterTokenid
            ? (this as any).filterTokenid(tokenid, coinList)
            : coinList.filter((c: any) => c.getValue?.().getTokenid?.() && Buffer.compare(c.getValue().getTokenid(), tokenid) === 0);

        // Sum the filtered coins
        const sum = (this as any).sum
            ? (this as any).sum(coinTokenList)
            : coinTokenList.reduce((acc: Coin, c: any) => acc.add(c.getValue()), Coin.valueOf(0n, tokenid));

        if (sum.compareTo(amount) < 0) {
            throw new InsufficientMoneyException(`to pay ${amount} account sum: ${sum}`);
        }

        // Split the coinTokenList into sublists of size 'split'
        const parts: any[][] = this.chopped(coinTokenList, split);

        const result: Block[] = [];
        let payAmount = amount;
        for (const part of parts) {
            // Sum this part
            const canPay = (this as any).sum
                ? (this as any).sum(part)
                : part.reduce((acc: Coin, c: any) => acc.add(c.getValue()), Coin.valueOf(0n, tokenid));

            // Get the latest tip block
            const tipBlock = await this.getTip();

            // Add transaction to the block
            const block = this.payFromListNoSplit(aesKey, destination, payAmount, memoInfo, part, tipBlock);
            result.push(block);

            if (canPay.compareTo(payAmount) >= 0) {
                break;
            }
            payAmount = payAmount.subtract(canPay);
        }

        // For each block, add fee transaction if needed, log, and solve/post
        for (const block of result) {
            if (typeof this.getFee === 'function' && this.getFee() && !amount.isBIG?.()) {
                // Add fee transaction (should be a Transaction, not a Block)
                if (typeof this.feeTransaction === 'function') {
                    const feeTxBlock = await this.feeTransaction(aesKey, coinList[0]?.getValue?.(), aesKey);
                    const feeTx = feeTxBlock?.getTransactions?.()?.[0];
                    if (feeTx && typeof block.addTransaction === 'function') {
                        block.addTransaction(feeTx);
                    }
                }
            }
            // Improved logging: print block hash and height if available
            Wallet.log.debug?.(
                "Block hash: %s, height: %s",
                block.getHash?.()?.toString?.() ?? '',
                block.getHeight?.() ?? ''
            );
            if (typeof this.solveAndPost === 'function') {
                await this.solveAndPost(block);
            }
        }
        return result;
    }

    /**
     * Pays money to a list of ECKey addresses with retry and sleep logic.
     * @param aesKey The AES key for decrypting private keys.
     * @param giveMoneyResult A map of address to amount (BigInt).
     * @param tokenid The token id as a Buffer or Uint8Array.
     * @param memo Memo string for the transaction.
     * @param coinList List of FreeStandingTransactionOutput (UTXO wrappers).
     * @param repeat Number of retry attempts.
     * @param sleepMs Sleep time in milliseconds between retries.
     * @returns A Promise resolving to the created Block.
     * @throws InsufficientMoneyException if funds are insufficient after retries.
     */
    public async payMoneyToECKeyList(
        aesKey: any,
        giveMoneyResult: Map<string, bigint>,
        tokenid: Uint8Array,
        memo: string,
        coinList: any[],
        repeat: number,
        sleepMs: number
    ): Promise<Block> {
        try {
            // filterTokenid may be a method or utility
            const filtered = (this as any).filterTokenid
                ? (this as any).filterTokenid(tokenid, coinList)
                : coinList.filter((c: any) => Buffer.compare(c.getValue?.().getTokenid?.(), tokenid) === 0);
            return await (this as any).payToList(aesKey, giveMoneyResult, tokenid, memo, filtered);
        } catch (e: any) {
            if (e instanceof InsufficientMoneyException) {
                Wallet.log.debug?.("InsufficientMoneyException  %o repeat time =%d sleep=%d", giveMoneyResult, repeat, sleepMs);
                if (repeat > 0) {
                    await new Promise(res => setTimeout(res, sleepMs));
                    // Recalculate coinList
                    const newCoinList = await this.calculateAllSpendCandidates(aesKey, false);
                    return this.payMoneyToECKeyList(aesKey, giveMoneyResult, tokenid, memo, newCoinList, repeat - 1, sleepMs);
                }
            } else if (e instanceof Error && e.message?.includes("net.bigtangle.core.exception.VerificationException$ConflictPossibleException")) {
                Wallet.log.debug?.("%s   %o repeat time =%d sleep=%d", e.message, giveMoneyResult, repeat, sleepMs);
                if (repeat > 0) {
                    await new Promise(res => setTimeout(res, sleepMs));
                    const newCoinList = await this.calculateAllSpendCandidates(aesKey, false);
                    return this.payMoneyToECKeyList(aesKey, giveMoneyResult, tokenid, memo, newCoinList, repeat - 1, sleepMs);
                }
            } else {
                throw e;
            }
        }
        throw new InsufficientMoneyException("InsufficientMoneyException " + JSON.stringify([...giveMoneyResult]));
    }

    /**
     * Solves the block (if required) and posts it to the server.
     * @param block The Block to solve and post.
     * @returns A Promise resolving to the posted Block.
     */
    public async solveAndPost(block: Block): Promise<Block> {
        if (typeof block.solve === 'function') {
            const result = block.solve();
            if (typeof result !== 'undefined' && typeof (result as any).then === 'function') {
                await Promise.resolve(result);
            }
        }
        // Serialize the block
        const blockBytes = block.bitcoinSerialize ? block.bitcoinSerialize() : Buffer.from([]);
        // Post to the server
        const url = this.getServerURL() + (ReqCmd.saveBlock || '/saveBlock');
        const response = await OkHttp3Util.post([url], blockBytes);
        if (response && this.params?.getDefaultSerializer) {
            return this.params.getDefaultSerializer().makeBlock(response);
        }
        return block;
    }

    /**
     * Solves the block and posts it to the signToken endpoint.
     * @param block The Block to solve and sign.
     * @returns A Promise resolving to the signed Block.
     */
    public async adjustSolveAndSign(block: Block): Promise<Block> {
        try {
            if (typeof block.solve === 'function') {
                const result = block.solve();
                if (typeof result !== 'undefined' && typeof (result as any).then === 'function') {
                    await Promise.resolve(result);
                }
            }
            await OkHttp3Util.post(
                [this.getServerURL() + (ReqCmd.signToken || '/signToken')],
                block.bitcoinSerialize ? block.bitcoinSerialize() : Buffer.from([])
            );
            return block;
        } catch (e: any) {
            if (e?.code === 'ECONNREFUSED' || e?.name === 'ConnectException') {
                this.serverConnectException();
            }
            throw e;
        }
    }

    /**
     * Handles server connection exceptions (stub).
     */
    protected serverConnectException(): void {
        Wallet.log.error?.('Server connection exception occurred.');
    }

    /**
     * Pays to a list of addresses with specified amounts, using the given UTXOs.
     * @param aesKey The AES key for decrypting private keys.
     * @param giveMoneyResult A map of address to amount (BigInt).
     * @param tokenid The token id as a Buffer or Uint8Array.
     * @param memo Memo string for the transaction.
     * @param coinList List of FreeStandingTransactionOutput (UTXO wrappers).
     * @returns A Promise resolving to the created and posted Block, or null if giveMoneyResult is empty.
     * @throws InsufficientMoneyException if funds are insufficient.
     */
    public async payToList(
        aesKey: any,
        giveMoneyResult: Map<string, bigint>,
        tokenid: Uint8Array,
        memo: string,
        coinList: any[]
    ): Promise<Block | null> {
        if (!giveMoneyResult || giveMoneyResult.size === 0) {
            return null;
        }
        // payToListTransaction should be implemented elsewhere
        const multispent: Transaction = await (this as any).payToListTransaction(aesKey, giveMoneyResult, tokenid, memo, coinList);

        const block: Block = await this.getTip();
        if (typeof block.addTransaction === 'function') {
            block.addTransaction(multispent);
        } else if (Array.isArray((block as any).transactions)) {
            (block as any).transactions.push(multispent);
        }

        // Check if a fee transaction is needed
        const mainTokenId = Buffer.from(NetworkParameters.BIGTANGLE_TOKENNAME || '', 'utf8');
        if (
            typeof this.getFee === 'function' &&
            this.getFee() &&
            !mainTokenId.equals(Buffer.from(tokenid))
        ) {
            if (typeof this.feeTransaction === 'function') {
                const feeTxBlock = await this.feeTransaction(aesKey, coinList[0]?.getValue?.(), aesKey);
                const feeTx = feeTxBlock?.getTransactions?.()?.[0];
                if (feeTx && typeof block.addTransaction === 'function') {
                    block.addTransaction(feeTx);
                }
            }
        }

        return await this.solveAndPost(block);
    }

    /**
     * Checks if the given token ID exists on the server.
     * Throws NoTokenException if not found.
     * @param tokenid The token ID to check (string or Buffer/Uint8Array).
     * @returns A Promise resolving to the Token object.
     * @throws NoTokenException if the token is not found.
     */
    public async checkTokenId(tokenid: string | Uint8Array): Promise<Token> {
        const tokenidStr = typeof tokenid === 'string' ? tokenid : Buffer.from(tokenid).toString('hex');
        const requestParam = { tokenid: tokenidStr };
        const url = this.getServerURL() + ( ReqCmd.getTokenById || '/getTokenById');
        const resp = await OkHttp3Util.post([url], Buffer.from(JSON.stringify(requestParam)));
        const responseStr = typeof resp === 'string' ? resp : resp.toString();
        const tokenResponse: GetTokensResponse = Json.jsonmapper().parse(responseStr);
        const tokens = tokenResponse?.getTokens?.();
        if (!tokens || tokens.length === 0) {
            throw new NoTokenException();
        }
        return tokens[0];
    }
}   
   
