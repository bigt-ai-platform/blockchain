import { NetworkParameters } from '../NetworkParameters';
import { UTXO } from '../UTXO';
import { Coin } from '../Coin';
import { Script } from '../Script';

export class FreeStandingTransactionOutput {
    private params: NetworkParameters;
    private utxo: UTXO;
    private value: Coin;
    private scriptPubKey: Script;

    constructor(params: NetworkParameters, utxo: UTXO) {
        this.params = params;
        this.utxo = utxo;
        this.value = utxo.getValue();
        this.scriptPubKey = new Script(utxo.getScriptBytes());
    }

    getUTXO(): UTXO {
        return this.utxo;
    }

    getValue(): Coin {
        return this.value;
    }

    getScriptPubKey(): Script {
        return this.scriptPubKey;
    }

    getScriptBytes(): Uint8Array {
        return this.scriptPubKey.getProgram();
    }
}
