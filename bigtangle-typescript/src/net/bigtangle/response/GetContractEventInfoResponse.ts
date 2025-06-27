import { AbstractResponse } from './AbstractResponse';
import { ContractEventInfo } from '../core/ContractEventInfo';

export class GetContractEventInfoResponse extends AbstractResponse {
    private outputs: ContractEventInfo[] | null = null;

    public getOutputs(): ContractEventInfo[] | null {
        return this.outputs;
    }

    public setOutputs(outputs: ContractEventInfo[] | null): void {
        this.outputs = outputs;
    }
}
