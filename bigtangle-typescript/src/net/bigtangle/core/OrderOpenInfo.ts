import { DataClass } from './DataClass';
import { NetworkParameters } from './NetworkParameters';
import { Side } from './Side';
import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class OrderOpenInfo extends DataClass {
    private static readonly FROMTIME = Math.floor(Date.now() / 1000) - 5;

    private targetValue: number = 0;
    private targetTokenid: string | null = null;
    private beneficiaryPubKey: Uint8Array | null = null;
    private validToTime: number | null = null;
    private validFromTime: number | null = null;
    private beneficiaryAddress: string | null = null;
    private orderBaseToken: string | null = null;
    private price: number | null = null;
    private offerValue: number = 0;
    private offerTokenid: string | null = null;
     
    constructor(
        targetValue?: number,
        targetTokenid?: string,
        beneficiaryPubKey?: Uint8Array,
        validToTimeMilli?: number,
        validFromTimeMilli?: number,
        side?: Side,
        beneficiaryAddress?: string,
        orderBaseToken?: string,
        price?: number,
        offerValue?: number,
        offerTokenid?: string
    ) {
        super();
        this.setVersion(2);
        if (targetValue !== undefined) this.targetValue = targetValue;
        if (targetTokenid) this.targetTokenid = targetTokenid;
        if (beneficiaryPubKey) this.beneficiaryPubKey = beneficiaryPubKey;

        if (validFromTimeMilli === undefined || validFromTimeMilli === null) {
            this.validFromTime = OrderOpenInfo.FROMTIME;
        } else {
            this.validFromTime = Math.floor(validFromTimeMilli / 1000);
        }
        if (validToTimeMilli === undefined || validToTimeMilli === null) {
            this.validToTime = (this.validFromTime || 0) + NetworkParameters.ORDER_TIMEOUT_MAX;
        } else {
            this.validToTime = Math.min(Math.floor(validToTimeMilli / 1000), (this.validFromTime || 0) + NetworkParameters.ORDER_TIMEOUT_MAX);
        }
        if (beneficiaryAddress) this.beneficiaryAddress = beneficiaryAddress;
        if (orderBaseToken) this.orderBaseToken = orderBaseToken;
        if (price !== undefined) this.price = price;
        if (offerValue !== undefined) this.offerValue = offerValue;
        if (offerTokenid) this.offerTokenid = offerTokenid;
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream(baos);
        try {
            dos.write(super.toByteArray());

            dos.writeLong(this.targetValue);
            dos.writeLong(this.validToTime || 0);
            dos.writeLong(this.validFromTime || 0);
            dos.writeInt(this.beneficiaryPubKey ? this.beneficiaryPubKey.length : 0);
            if (this.beneficiaryPubKey) {
                dos.write(this.beneficiaryPubKey);
            }

            dos.writeBoolean(this.targetTokenid !== null);
            if (this.targetTokenid !== null) {
                const bytes = new TextEncoder().encode(this.targetTokenid);
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }

            dos.writeBoolean(this.beneficiaryAddress !== null);
            if (this.beneficiaryAddress !== null) {
                const bytes = new TextEncoder().encode(this.beneficiaryAddress);
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
            dos.writeBoolean(this.orderBaseToken !== null);
            if (this.orderBaseToken !== null) {
                const bytes = new TextEncoder().encode(this.orderBaseToken);
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
            dos.writeLong(this.price || 0);
            dos.writeLong(this.offerValue);
            dos.writeBoolean(this.offerTokenid !== null);
            if (this.offerTokenid !== null) {
                const bytes = new TextEncoder().encode(this.offerTokenid);
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
            dos.close();
        } catch (e: any) {
           throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parseDIS(dis: DataInputStream): OrderOpenInfo {
        super.parseDIS(dis); 
        this.targetValue = dis.readLong();
        this.validToTime = dis.readLong();
        this.validFromTime = dis.readLong();
        const size = dis.readInt();
        this.beneficiaryPubKey = dis.readBytes(size);
        this.targetTokenid = dis.readNBytesString();
        this.beneficiaryAddress = dis.readNBytesString();
        if (this.getVersion() > 1) {
            this.orderBaseToken = dis.readNBytesString();
            this.price = dis.readLong();
            this.offerValue = dis.readLong();
            this.offerTokenid = dis.readNBytesString();      
        } else {
            this.orderBaseToken = NetworkParameters.BIGTANGLE_TOKENID_STRING; 
        }
        return this;
    }

    public parse(buf: Uint8Array): OrderOpenInfo {
        const bain = new DataInputStream(buf);
        try {
            this.parseDIS(bain);
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }

    public buy(): boolean {
        return  this.targetTokenid !== null && this.orderBaseToken !== null && !this.targetTokenid.equals(this.getOrderBaseToken());
    }

    public toString(): string {
        return `OrderOpenInfo  \n targetValue=${this.targetValue}, \n targetTokenid=${this.targetTokenid}` +
               `, \n validToTime=${this.validToTime},  \n validFromTime=${this.validFromTime}` +
               `, \n beneficiaryAddress=${this.beneficiaryAddress} \n offerValue=${this.offerValue}` +
               `, \n offerTokenid=${this.offerTokenid}, \n orderBaseToken=${this.orderBaseToken}` +
               `, \n price=${this.price}`;
    }

    public getValidToTime(): number | null {
        return this.validToTime;
    }

    public setValidToTime(validToTime: number | null): void {
        this.validToTime = validToTime;
    }

    public getValidFromTime(): number | null {
        return this.validFromTime;
    }

    public setValidFromTime(validFromTime: number | null): void {
        this.validFromTime = validFromTime;
    }

    public getBeneficiaryAddress(): string | null {
        return this.beneficiaryAddress;
    }

    public setBeneficiaryAddress(beneficiaryAddress: string | null): void {
        this.beneficiaryAddress = beneficiaryAddress;
    }

    public getOrderBaseToken(): string | null {
        return this.orderBaseToken;
    }

    public setOrderBaseToken(orderBaseToken: string | null): void {
        this.orderBaseToken = orderBaseToken;
    }

    public getPrice(): number | null {
        return this.price;
    }

    public setPrice(price: number | null): void {
        this.price = price;
    }

    public getBeneficiaryPubKey(): Uint8Array | null {
        return this.beneficiaryPubKey;
    }

    public setBeneficiaryPubKey(beneficiaryPubKey: Uint8Array | null): void {
        this.beneficiaryPubKey = beneficiaryPubKey;
    }

    public getTargetValue(): number {
        return this.targetValue;
    }

    public setTargetValue(targetValue: number): void {
        this.targetValue = targetValue;
    }

    public getTargetTokenid(): string | null {
        return this.targetTokenid;
    }

    public setTargetTokenid(targetTokenid: string | null): void {
        this.targetTokenid = targetTokenid;
    }

    public getOfferValue(): number {
        return this.offerValue;
    }

    public setOfferValue(offerValue: number): void {
        this.offerValue = offerValue;
    }

    public getOfferTokenid(): string | null {
        return this.offerTokenid;
    }

    public setOfferTokenid(offerTokenid: string | null): void {
        this.offerTokenid = offerTokenid;
    }
}