import { UTXO } from '../UTXO';

export class OutputsDetailsResponse {
    private outputs: UTXO;

    constructor(outputs: UTXO) {
        this.outputs = outputs;
    }

    getOutputs(): UTXO {
        return this.outputs;
    }

    setOutputs(outputs: UTXO): void {
        this.outputs = outputs;
    }
}
