import { Sha256Hash } from './Sha256Hash';
import { SpentBlock } from './SpentBlock';
import { BigInteger } from './BigInteger';

export class ContractEventRecord extends SpentBlock {
    private collectinghash: Sha256Hash | null = null;
    private contractTokenid: string | null = null;
    private targetValue: BigInteger | null = null;
    private targetTokenid: string | null = null;
    private beneficiaryAddress: string | null = null;

    constructor(
        initialBlockHash?: Sha256Hash,
        collectinghash?: Sha256Hash,
        contractTokenid?: string,
        confirmed?: boolean,
        spent?: boolean,
        spenderBlockHash?: Sha256Hash,
        targetValue?: BigInteger,
        targetTokenid?: string,
        beneficiaryAddress?: string
    ) {
        super();
        if (initialBlockHash) this.setBlockHash(initialBlockHash);
        if (collectinghash) this.collectinghash = collectinghash;
        if (confirmed !== undefined) this.setConfirmed(confirmed);
        if (spent !== undefined) this.setSpent(spent);
        if (spenderBlockHash) this.setSpenderBlockHash(spenderBlockHash);
        if (targetValue) this.targetValue = targetValue;
        if (targetTokenid) this.targetTokenid = targetTokenid;
        if (beneficiaryAddress) this.beneficiaryAddress = beneficiaryAddress;
        if (contractTokenid) this.contractTokenid = contractTokenid;
    }

    public static cloneOrderRecord(old: ContractEventRecord): ContractEventRecord {
        return new ContractEventRecord(
            old.getBlockHash() || undefined,
            old.getCollectinghash() || undefined,
            old.getContractTokenid() || undefined,
            old.isConfirmed(),
            old.isSpent(),
            old.getSpenderBlockHash() || undefined,
            old.getTargetValue() || undefined,
            old.getTargetTokenid() || undefined,
            old.getBeneficiaryAddress() || undefined
        );
    }

    public getTargetValue(): BigInteger | null {
        return this.targetValue;
    }

    public setTargetValue(targetValue: BigInteger | null): void {
        this.targetValue = targetValue;
    }

    public getTargetTokenid(): string | null {
        return this.targetTokenid;
    }

    public setTargetTokenid(targetTokenid: string | null): void {
        this.targetTokenid = targetTokenid;
    }

    public getBeneficiaryAddress(): string | null {
        return this.beneficiaryAddress;
    }

    public setBeneficiaryAddress(beneficiaryAddress: string | null): void {
        this.beneficiaryAddress = beneficiaryAddress;
    }

    public getContractTokenid(): string | null {
        return this.contractTokenid;
    }

    public setContractTokenid(contractTokenid: string | null): void {
        this.contractTokenid = contractTokenid;
    }

    public getCollectinghash(): Sha256Hash | null {
        return this.collectinghash;
    }

    public setCollectinghash(collectinghash: Sha256Hash | null): void {
        this.collectinghash = collectinghash;
    }

    public toString(): string {
        return `ContractEventRecord [ beneficiaryAddress=${this.beneficiaryAddress}, collectinghash=${this.collectinghash}` +
               `, contractTokenid=${this.contractTokenid}, targetValue=${this.targetValue}, targetTokenid=${this.targetTokenid}` +
               `${super.toString()}]`;
    }
}
