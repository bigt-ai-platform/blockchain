
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { ChildNumber } from '../../src/net/bigtangle/crypto/ChildNumber';
import { DeterministicKey } from '../../src/net/bigtangle/crypto/DeterministicKey';
import {
    CustomTransactionSigner,
    SignatureAndKey,
} from '../../src/net/bigtangle/signers/CustomTransactionSigner';
import { DeterministicKeyChain } from '../../src/net/bigtangle/wallet/DeterministicKeyChain';

/**
 * <p>Transaction signer which uses provided keychain to get signing keys from. It relies on previous signer to provide
 * derivation path to be used to get signing key and, once gets the key, just signs given transaction immediately.</p>
 * It should not be used in test scenarios involving serialization as it doesn't have proper serialize/deserialize
 * implementation.
 */
export class KeyChainTransactionSigner extends CustomTransactionSigner {
    private keyChain: DeterministicKeyChain;

    public constructor(keyChain?: DeterministicKeyChain) {
        super();
        if (keyChain) {
            this.keyChain = keyChain;
        }
    }

    protected getSignature(
        sighash: Sha256Hash,
        derivationPath: ChildNumber[],
    ): SignatureAndKey {
        const keyPath = [...derivationPath];
        const key = this.keyChain.getKeyByPath(keyPath, true);
        return new SignatureAndKey(
            key.sign(sighash),
            key.dropPrivateBytes().dropParent(),
        );
    }
}
