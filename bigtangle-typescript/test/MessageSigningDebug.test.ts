import { ECKey } from '../ECKey';
import { Sha256Hash } from '../Sha256Hash';
import * as crypto from 'crypto';
import { Utils } from '../Utils';
import bigInt from 'big-integer';

describe('Message Signing Debug', () => {
    it('should correctly format and hash a message', () => {
        const message = "Test message for signing";
        
        // Format message
        const data = Utils.formatMessageForSigning(message);
        console.log("Formatted data:", Utils.bytesToHex(data));
        
        // Double SHA256 hash
        const hash = Sha256Hash.twiceOf(data);
        console.log("Computed hash:", hash.toString());
        
        // Expected hash from known implementation
        const expectedHash = crypto.createHash('sha256').update(
            crypto.createHash('sha256').update(data).digest()
        ).digest();
        console.log("Expected hash:", Utils.bytesToHex(new Uint8Array(expectedHash)));
        
        expect(Utils.arraysEqual(hash.getBytes(), new Uint8Array(expectedHash))).toBeTruthy();
    });

    it('should sign and verify with known keys', () => {
        // Create a known private key
        const privateKey = bigInt("11253563012059685825953619222107823549092147699031672238385790369351542642445");
        const key = ECKey.fromPrivate(privateKey);
        
        const message = "Test message for signing";
        const signature = key.signMessage(message);
        
        // Verify the signature
        const result = key.verifyMessage(message, signature);
        expect(result).toBeTruthy();
    });
});
