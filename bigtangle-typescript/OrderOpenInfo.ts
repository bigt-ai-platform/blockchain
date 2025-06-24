export class OrderOpenInfo {
    private targetValue: number;
    private targetTokenId: string;
    private beneficiaryPubKey: Uint8Array;
    private validToTime: number | null;
    private validFromTime: number | null;
    private side: any; // Placeholder for Side enum
    private beneficiaryAddress: string;
    private orderBaseToken: string;
    private price: number;
    private offerValue: number;
    private offerTokenId: string;

    constructor(
        targetValue: number,
        targetTokenId: string,
        beneficiaryPubKey: Uint8Array,
        validToTime: number | null,
        validFromTime: number | null,
        side: any,
        beneficiaryAddress: string,
        orderBaseToken: string,
        price: number,
        offerValue: number,
        offerTokenId: string
    ) {
        this.targetValue = targetValue;
        this.targetTokenId = targetTokenId;
        this.beneficiaryPubKey = beneficiaryPubKey;
        this.validToTime = validToTime;
        this.validFromTime = validFromTime;
        this.side = side;
        this.beneficiaryAddress = beneficiaryAddress;
        this.orderBaseToken = orderBaseToken;
        this.price = price;
        this.offerValue = offerValue;
        this.offerTokenId = offerTokenId;
    }

    toByteArray(): Uint8Array {
        // Simplified implementation for now.
        // In a real scenario, this would serialize the object to a byte array.
        return new Uint8Array();
    }
}
