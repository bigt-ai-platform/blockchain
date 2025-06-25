import { ec } from 'elliptic';
import { Utils } from './Utils';
import { Sha256Hash } from './Sha256Hash';

const secp256k1 = new ec('secp256k1');

// Generate a new key pair
const keyPair = secp256k1.genKeyPair();
console.log('Private key:', keyPair.getPrivate('hex'));
console.log('Public key:', keyPair.getPublic(true, 'hex'));

// Create a message to sign
const message = 'Hello, BigTangle!';
console.log('Message:', message);

// Format message for signing (Bitcoin-style prefix)
const formattedMessage = Utils.formatMessageForSigning(message);
console.log('Formatted message:', Utils.bytesToHex(formattedMessage));

// Create message hash
const hash = Sha256Hash.of(formattedMessage);
console.log('Message hash:', hash.toString());

// Sign the message
const signature = keyPair.sign(hash.getBytes());
console.log('Signature:', signature.toDER('hex'));

// Verify the signature
const verifyResult = keyPair.verify(hash.getBytes(), signature);
console.log('Verification result:', verifyResult);

// Try to recover public key from signature
if (signature.recoveryParam === null) {
    console.error('Recovery parameter is null, cannot recover public key');
} else {
    const recoveredKey = secp256k1.recoverPubKey(
        hash.getBytes(),
        signature,
        signature.recoveryParam,
        'hex'
    );
    console.log('Recovered public key:', recoveredKey.encode('hex', true));
    
    // Compare recovered key with original
    console.log('Keys match:', keyPair.getPublic(true, 'hex') === recoveredKey.encode('hex', true));
}
