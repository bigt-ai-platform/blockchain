import { StatelessTransactionSigner } from './StatelessTransactionSigner';
import { TransactionInput } from '../core/TransactionInput';
import { Script } from '../script/Script';
import { ScriptChunk } from '../script/ScriptChunk';
import { TransactionSignature } from '../crypto/TransactionSignature';
import { ECKey } from '../core/ECKey';
import { KeyBag } from '../wallet/KeyBag'; // Placeholder
import { MissingPrivateKeyException } from '../crypto/MissingPrivateKeyException';
import { MissingSignatureException } from '../exception/Exceptions'; // Placeholder

export namespace Wallet {
    export enum MissingSigsMode {
        USE_OP_ZERO,
        THROW,
        USE_DUMMY_SIG,
    }
}

/**
 * This transaction signer resolves missing signatures in accordance with the
 * given {@link net.bigtangle.wallet.Wallet.MissingSigsMode}. If missingSigsMode
 * is USE_OP_ZERO this signer does nothing assuming missing signatures are
 * already presented in scriptSigs as OP_0. In MissingSigsMode.THROW mode this
 * signer will throw an exception. It would be MissingSignatureException for
 * P2SH or MissingPrivateKeyException for other transaction types.
 */
export class MissingSigResolutionSigner extends StatelessTransactionSigner {
    public missingSigsMode: Wallet.MissingSigsMode = Wallet.MissingSigsMode.USE_DUMMY_SIG;

    constructor(missingSigsMode?: Wallet.MissingSigsMode) {
        super();
        if (missingSigsMode !== undefined) {
            this.missingSigsMode = missingSigsMode;
        }
    }

    public isReady(): boolean {
        return true;
    }

    public signInputs(propTx: any, keyBag: KeyBag): boolean {
        if (this.missingSigsMode === Wallet.MissingSigsMode.USE_OP_ZERO) {
            return true;
        }

        const dummySig = TransactionSignature.dummy().encodeToBitcoin();
        const numInputs = propTx.partialTx.getInputs().length;
        for (let i = 0; i < numInputs; i++) {
            const txIn: TransactionInput = propTx.partialTx.getInput(i);
            if (txIn.getConnectedOutput() === null) {
                console.warn(`Missing connected output, assuming input ${i} is already signed.`);
                continue;
            }

            const scriptPubKey = txIn.getConnectedOutput()!.getScriptPubKey();
            const inputScript = txIn.getScriptSig();
            if (scriptPubKey.isPayToScriptHash() || scriptPubKey.isSentToMultiSig()) {
                const sigSuffixCount = scriptPubKey.isPayToScriptHash() ? 1 : 0;
                // all chunks except the first one (OP_0) and the last (redeem script) are signatures
                for (let j = 1; j < inputScript.getChunks().length - sigSuffixCount; j++) {
                    const scriptChunk: ScriptChunk = inputScript.getChunks()[j];
                    if (scriptChunk.equalsOpCode(0)) {
                        if (this.missingSigsMode === Wallet.MissingSigsMode.THROW) {
                            throw new MissingSignatureException();
                        } else if (this.missingSigsMode === Wallet.MissingSigsMode.USE_DUMMY_SIG) {
                            txIn.setScriptSig(scriptPubKey.getScriptSigWithSignature(inputScript, dummySig, j - 1));
                        }
                    }
                }
            } else {
                if (!inputScript.getChunks().isEmpty()) {
                    if (inputScript.getChunks()[0].equalsOpCode(0)) {
                        if (this.missingSigsMode === Wallet.MissingSigsMode.THROW) {
                            throw new MissingPrivateKeyException();
                        } else if (this.missingSigsMode === Wallet.MissingSigsMode.USE_DUMMY_SIG) {
                            txIn.setScriptSig(scriptPubKey.getScriptSigWithSignature(inputScript, dummySig, 0));
                        }
                    }
                }
            }
            // TODO handle non-P2SH multisig
        }
        return true;
    }
}
