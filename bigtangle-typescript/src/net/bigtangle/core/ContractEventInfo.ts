import { BigInteger } from 'jsbn';

export class ContractEventInfo {
    private contractTokenid: string;
    private payAmount: BigInteger;
    private tokenId: string;
    private beneficiaryAddress: string;
    private validToTime: number | null;
    private validFromTime: number | null;
    private memo: string;

    constructor(
        contractTokenid: string,
        payAmount: BigInteger,
        tokenId: string,
        beneficiaryAddress: string,
        validToTime: number | null,
        validFromTime: number | null,
        memo: string
    ) {
        this.contractTokenid = contractTokenid;
        this.payAmount = payAmount;
        this.tokenId = tokenId;
        this.beneficiaryAddress = beneficiaryAddress;
        this.validToTime = validToTime;
        this.validFromTime = validFromTime;
        this.memo = memo;
    }

    toByteArray(): Uint8Array {
        // Simplified implementation for now.
        // In a real scenario, this would serialize the object to a byte array.
        return new Uint8Array();
    }
}
