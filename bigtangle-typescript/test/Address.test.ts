import { Address } from '../Address';
import { MainNetParams } from '../MainNetParams';
import { Utils } from '../Utils';
import { ECKey } from '../ECKey';

describe('Address', () => {
    const params = MainNetParams.get();
    
    it('should create a valid address from public key', () => {
        // Create a dummy public key
        const pubKey = new Uint8Array(33);
        pubKey[0] = 0x02; // Compressed public key prefix
        for (let i = 1; i < 33; i++) {
            pubKey[i] = i;
        }
        
        const ecKey = ECKey.fromPublicOnly(pubKey);
        const address = Address.fromKey(params, ecKey);
        
        expect(address).toBeDefined();
        expect(address.toString().length).toBeGreaterThan(20);
    });

    it('should create a valid address from base58 string', () => {
        // Create a valid address first
        const pubKey = new Uint8Array(33);
        pubKey[0] = 0x02; // Compressed public key prefix
        for (let i = 1; i < 33; i++) {
            pubKey[i] = i;
        }
        
        const ecKey = ECKey.fromPublicOnly(pubKey);
        const address1 = Address.fromKey(params, ecKey);
        const base58 = address1.toString();
        
        // Now create from base58
        const address2 = new Address(params, base58);
        
        expect(address2).toBeDefined();
        expect(address2.toString()).toEqual(base58);
    });

    it('should detect P2SH addresses', () => {
        const p2shAddress = new Address(params, 0, Utils.HEX.decode("0011223344556677889900112233445566778899"));
        expect(p2shAddress.isP2SHAddress()).toBe(false);
        
        // Create a P2SH address
        const p2sh = new Address(params, params.getP2SHHeader(), Utils.HEX.decode("0011223344556677889900112233445566778899"));
        expect(p2sh.isP2SHAddress()).toBe(true);
    });
});
