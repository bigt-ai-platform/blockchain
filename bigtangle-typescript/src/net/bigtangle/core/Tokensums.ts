import { DataClass } from './DataClass';
import { BigIntegerJSBN as BigIntegerJSBNJSBN } from 'jsbn';
import { UTXO } from './UTXO';
import { OrderRecord } from './OrderRecord';
import { ContractEventRecord } from './ContractEventRecord';
import { NetworkParameters } from './NetworkParameters';
import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class Tokensums extends DataClass {
    public tokenid: string | null = null;
    public initial: BigIntegerJSBN = BigIntegerJSBN.ZERO;
    public unspent: BigIntegerJSBN = BigIntegerJSBN.ZERO;
    public order: BigIntegerJSBN = BigIntegerJSBN.ZERO;
    public contract: BigIntegerJSBN = BigIntegerJSBN.ZERO;
    public utxos: UTXO[] = [];
    public orders: OrderRecord[] = [];
    public contracts: ContractEventRecord[] = [];

    public calculate(): void {
        this.calcOutputs();
        this.ordersum();
        this.contractsum();
    }
    
    public calcOutputs(): void {
        for (const u of this.utxos) {
            if (u.isConfirmed() && !u.isSpent()) {
                this.unspent = this.unspent.add(u.getValue().getValue());
            } 
        }
    }

    public ordersum(): void {
        for (const orderRecord of this.orders) {
            if (orderRecord.getOfferTokenid() === this.tokenid) {
                this.order = this.order.add(new BigIntegerJSBN(orderRecord.getOfferValue().toString()));
            }
        }
    }

    public contractsum(): void {
        for (const c of this.contracts) {
            if (c.getTargetTokenid() === this.tokenid) {
                this.contract = this.contract.add(c.getTargetValue() || BigIntegerJSBN.ZERO);
            }
        }
    }

    public toString(): string {
        return `Tokensums [ \n tokenid=${this.tokenid},  \n initial=${this.initial}, \n unspent UTXO=${this.unspent}` +
               `, \n order=${this.order}` +
               `, \n contract=${this.contract}` +
               ` \n unspentUTXO.add(order).add(contract) = ${this.unspent.add(this.order).add(this.contract)}]`;
    }

    public check(): boolean {
        if (NetworkParameters.BIGTANGLE_TOKENID_STRING === this.tokenid) {
            //fee payment, only create reward block will fee to miner
            return this.initial.compareTo(this.unspent.add(this.order).add(this.contract)) >= 0;
        } else {
            return this.initial.compareTo(this.unspent.add(this.order).add(this.contract)) === 0;
        }
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream(baos);
        try {
            dos.write(super.toByteArray());
            dos.writeNBytesString(this.tokenid || "");
            dos.writeBytes(Utils.bigIntToBytes(this.initial, 32)); // Assuming 32 bytes for BigIntegerJSBN
            dos.writeBytes(Utils.bigIntToBytes(this.unspent, 32));
            dos.writeBytes(Utils.bigIntToBytes(this.order, 32));
            dos.writeInt(this.utxos.length);
            for (const c of this.utxos) {
                dos.write(c.toByteArray());
            }
            dos.writeInt(this.orders.length);
            for (const c of this.orders) {
                dos.write(c.toByteArray());
            }
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }
 
    public unspentOrderSum(): BigIntegerJSBN {
        return this.unspent.add(this.order);
    }

    public getTokenid(): string | null {
        return this.tokenid;
    }

    public setTokenid(tokenid: string | null): void {
        this.tokenid = tokenid;
    }

    public getInitial(): BigIntegerJSBN {
        return this.initial;
    }

    public setInitial(initial: BigIntegerJSBN): void {
        this.initial = initial;
    }

    public getUnspent(): BigIntegerJSBN {
        return this.unspent;
    }

    public setUnspent(unspent: BigIntegerJSBN): void {
        this.unspent = unspent;
    }

    public getOrder(): BigIntegerJSBN {
        return this.order;
    }

    public setOrder(order: BigIntegerJSBN): void {
        this.order = order;
    }

    public getUtxos(): UTXO[] {
        return this.utxos;
    }

    public setUtxos(utxos: UTXO[]): void {
        this.utxos = utxos;
    }

    public getOrders(): OrderRecord[] {
        return this.orders;
    }

    public setOrders(orders: OrderRecord[]): void {
        this.orders = orders;
    }

    public getContract(): BigIntegerJSBN {
        return this.contract;
    }

    public setContract(contract: BigIntegerJSBN): void {
        this.contract = contract;
    }

    public getContracts(): ContractEventRecord[] {
        return this.contracts;
    }

    public setContracts(contracts: ContractEventRecord[]): void {
        this.contracts = contracts;
    }
}