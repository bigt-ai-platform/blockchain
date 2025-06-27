import { DataClass } from './DataClass';
import { BigInteger } from './BigInteger';
import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class ContractEventInfo extends DataClass {
    private beneficiaryAddress: string | null = null;
    private offerValue: BigInteger | null = null;
    private offerTokenid: string | null = null;
    private contractTokenid: string | null = null;
    private offerSystem: string | null = null;

    constructor(
        contractTokenid?: string,
        offerValue?: BigInteger,
        offerTokenid?: string,
        beneficiaryAddress?: string,
        validToTimeMilli?: number,
        validFromTimeMilli?: number,
        offerSystem?: string
    ) {
        super();
        if (contractTokenid) this.contractTokenid = contractTokenid;
        if (beneficiaryAddress) this.beneficiaryAddress = beneficiaryAddress;
        if (offerValue) this.offerValue = offerValue;
        if (offerTokenid) this.offerTokenid = offerTokenid;
        if (offerSystem) this.offerSystem = offerSystem;
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream();
        try {
            dos.write(Buffer.from(super.toByteArray()));
            dos.writeNBytesString(this.beneficiaryAddress || "");
            dos.writeNBytesString(this.offerTokenid || "");
            dos.writeNBytesString(this.contractTokenid || "");
            dos.writeNBytesString(this.offerSystem || "");
            dos.writeBytes(
                Buffer.from(this.offerValue ? Utils.bigIntToBytes(this.offerValue, 32) : new Uint8Array(32))
            ); // Assuming 32 bytes for BigInteger
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parseDIS(dis: DataInputStream): ContractEventInfo {
        super.parseDIS(dis);
        this.beneficiaryAddress = dis.readNBytesString();
        this.offerTokenid = dis.readNBytesString();
        this.contractTokenid = dis.readNBytesString();
        this.offerSystem = dis.readNBytesString();
        this.offerValue = new BigInteger(Utils.HEX.encode(dis.readBytes(32)), 16);
        return this;
    }

    public parse(buf: Uint8Array): ContractEventInfo {
        const bain = new DataInputStream(Buffer.from(buf));
        try {
            this.parseDIS(bain);
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }

    public parseChecked(buf: Uint8Array): ContractEventInfo {
        try {
            return this.parse(buf);
        } catch (e: any) {
            throw new Error(e);
        }
    }

    public getBeneficiaryAddress(): string | null {
        return this.beneficiaryAddress;
    }

    public setBeneficiaryAddress(beneficiaryAddress: string | null): void {
        this.beneficiaryAddress = beneficiaryAddress;
    }

    public getOfferValue(): BigInteger | null {
        return this.offerValue;
    }

    public setOfferValue(offerValue: BigInteger | null): void {
        this.offerValue = offerValue;
    }

    public getOfferTokenid(): string | null {
        return this.offerTokenid;
    }

    public setOfferTokenid(offerTokenid: string | null): void {
        this.offerTokenid = offerTokenid;
    }

    public getContractTokenid(): string | null {
        return this.contractTokenid;
    }

    public setContractTokenid(contractTokenid: string | null): void {
        this.contractTokenid = contractTokenid;
    }

    public getOfferSystem(): string | null {
        return this.offerSystem;
    }

    public setOfferSystem(offerSystem: string | null): void {
        this.offerSystem = offerSystem;
    }

    public toString(): string {
        return `ContractEventInfo [beneficiaryAddress=${this.beneficiaryAddress}, offerValue=${this.offerValue}` +
               `, offerTokenid=${this.offerTokenid}, contractTokenid=${this.contractTokenid}, offerSystem=${this.offerSystem}]`;
    }
}