package net.bigtangle.server.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Address;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class AccessGrantService {

    @Autowired
    protected NetworkParameters networkParameters;
    @Autowired
    protected  StoreService storeService;
    
    public void addAccessGrant(String pubKey,BlockStoreInterface store) throws BlockStoreException {
        byte[] buf = Utils.HEX.decode(pubKey);
        ECKey ecKey = ECKey.fromPublicOnly(buf);
        Address address = ecKey.toAddress(networkParameters); 
        store. insertAccessGrant(address.toBase58());
    
    }

    public void deleteAccessGrant(String pubKey,BlockStoreInterface store) throws BlockStoreException 
    {
        byte[] buf = Utils.HEX.decode(pubKey);
        ECKey ecKey = ECKey.fromPublicOnly(buf);
        Address address = ecKey.toAddress(networkParameters);
        store .deleteAccessGrant(address.toBase58());
    }

    public int getCountAccessGrantByAddress(String address, BlockStoreInterface store) {
        try {
            return store.getCountAccessGrantByAddress(address);
        } catch (Exception e) {
            return 0;
        }
    }
}
