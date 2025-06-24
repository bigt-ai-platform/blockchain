import { ec } from 'elliptic';

const secp256k1 = new ec('secp256k1');

function testEllipticSigning() {
    // Generate key pair
    const keyPair = secp256k1.genKeyPair();
    console.log('Private key:', keyPair.getPrivate('hex'));
    console.log('Public key:', keyPair.getPublic('hex'));

    // Create message
    const message = 'Test message';
    console.log('Message:', message);

    // Sign message
    const signature = keyPair.sign(message);
    console.log('Signature:', signature.toDER('hex'));

    // Verify signature
    const verified = keyPair.verify(message, signature);
    console.log('Verified:', verified);
}

testEllipticSigning();
