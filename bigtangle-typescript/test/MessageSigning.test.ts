import { ECKey } from '../ECKey';
import { Utils } from '../Utils';

describe('Message Signing', () => {
  it('should sign and verify a message', () => {
    const message = "Test message for signing";
    const key = ECKey.create();
    
    // Sign message - now returns ECDSASignature object
    const signature = key.signMessage(message);
    
    // Verify message - now returns boolean
    expect(key.verifyMessage(message, signature)).toBeTruthy();
    
    // Test with different message
    expect(key.verifyMessage("Different message", signature)).toBeFalsy();
  });
  
  it('should work with fixed keys and messages', () => {
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
