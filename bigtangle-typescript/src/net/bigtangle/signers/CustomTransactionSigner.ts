import { StatelessTransactionSigner } from './StatelessTransactionSigner';
import { Sha256Hash } from '../core/Sha256Hash';
import { Transaction } from '../core/Transaction';
import { TransactionInput } from '../core/TransactionInput';
import { TransactionOutput } from '../core/TransactionOutput';
import { Script } from '../script/Script';
import { ECKey } from '../core/ECKey';
import { TransactionSignature } from '../crypto/TransactionSignature';
import { KeyBag } from '../wallet/KeyBag'; // Placeholder
import { RedeemData } from '../wallet/RedeemData'; // Placeholder
import { ChildNumber } from '../crypto/ChildNumber';
import { ECDSASignature } from '../crypto/ECDSASignature';

/**
 * <p>This signer may be used as a template for creating custom multisig transaction signers.</p>
 * <p>
 * Concrete implementations have to implement {@link #getSignature(net.bigtangle.core.Sha256Hash, java.util.List)}
 * method returning a signature and a public key of the keypair used to created that signature.
 * It's up to custom implementation where to locate signatures: it may be a network connection,
 * some local API or something else.
 * </p>
 */
export abstract class CustomTransactionSigner extends StatelessTransactionSigner {

    public isReady(): boolean {
        return true;
    }

    public signInputs(propTx: any, keyBag: KeyBag): boolean {
        const tx: Transaction = propTx.partialTx;
        const numInputs = tx.getInputs().length;
        for (let i = 0; i < numInputs; i++) {
            const txIn = tx.getInput(i);
            const txOut = txIn.getConnectedOutput();
            if (txOut === null) {
                continue;
            }
            const scriptPubKey = txOut.getScriptPubKey();
            if (!scriptPubKey.isPayToScriptHash()) {
                console.warn("CustomTransactionSigner works only with P2SH transactions");
                return false;
            }

            let inputScript = txIn.getScriptSig();

            try {
                // We assume if its already signed, its hopefully got a SIGHASH type that will not invalidate when
                // we sign missing pieces (to check this would require either assuming any signatures are signing
                // standard output types or a way to get processed signatures out of script execution)
                inputScript.correctlySpends(tx, i, txIn.getConnectedOutput()!.getScriptPubKey(), Script.ALL_VERIFY_FLAGS);
                console.warn(`Input ${i} already correctly spends output, assuming SIGHASH type used will be safe and skipping signing.`);
                continue;
            } catch (e) {
                // Expected.
            }

            const redeemData = txIn.getConnectedRedeemData(keyBag);
            if (redeemData === null) {
                console.warn(`No redeem data found for input ${i}`);
                continue;
            }

            const sighash = tx.hashForSignature(i, redeemData.redeemScript, Transaction.SigHash.ALL, false);
            const sigKey = this.getSignature(sighash, propTx.keyPaths.get(scriptPubKey));
            const txSig = new TransactionSignature(sigKey.sig.r, sigKey.sig.s, Transaction.SigHash.ALL);
            const sigIndex = inputScript.getSigInsertionIndex(sighash, sigKey.pubKey);
            inputScript = scriptPubKey.getScriptSigWithSignature(inputScript, txSig.encodeToBitcoin(), sigIndex);
            txIn.setScriptSig(inputScript);
        }
        return true;
    }

    protected abstract getSignature(sighash: Sha256Hash, derivationPath: ChildNumber[]): CustomTransactionSigner.SignatureAndKey;

}

export namespace CustomTransactionSigner {
    export class SignatureAndKey {
        public readonly sig: ECDSASignature;
        public readonly pubKey: ECKey;

        constructor(sig: ECDSASignature, pubKey: ECKey) {
            this.sig = sig;
            this.pubKey = pubKey;
        }
    }
}
