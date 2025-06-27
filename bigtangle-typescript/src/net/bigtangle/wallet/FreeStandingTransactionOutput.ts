import { NetworkParameters } from '../core/NetworkParameters';
import { Sha256Hash } from '../core/Sha256Hash';
import { TransactionOutput } from '../core/TransactionOutput';
import { UTXO } from '../core/UTXO';

export class FreeStandingTransactionOutput extends TransactionOutput {
    private output: UTXO;
    
    constructor(params: NetworkParameters, output: UTXO) {
        // Defensive: check for null/undefined values and provide defaults if needed
        const value = output.getValue ? output.getValue() : undefined;
        const script = output.getScript ? output.getScript() : undefined;
        const program = script?.getProgram?.() ?? new Uint8Array();
        if (value === undefined || value === null) {
            throw new Error('UTXO.getValue() returned undefined/null, cannot construct FreeStandingTransactionOutput');
        }
        // Convert Uint8Array to Buffer for compatibility
        const programBuffer = Buffer.from(program);
        super(params, null, value, programBuffer);
        this.output = output;
    }

    public getUTXO(): UTXO {
        return this.output;
    }
 
    public getIndex(): number {
        return this.output.getIndex();
    }

    public getParentTransactionHash(): Sha256Hash | null {
        return this.output.getTxHash();
    }
}
