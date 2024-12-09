package net.bigtangle.server.service;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.ECKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;
import net.bigtangle.core.response.AbstractResponse;
import net.bigtangle.core.response.SessionRandomNumResponse;
import net.bigtangle.encrypt.ECIESCoder;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class AccessPermissionedService {

    
    @Autowired
    protected  StoreService storeService;
    public AbstractResponse getSessionRandomNumResp(String pubKey,BlockStoreInterface store) throws Exception {
        ECKey ecKey = ECKey.fromPublicOnly(Utils.HEX.decode(pubKey));

        String message = UUID.randomUUID().toString();
        byte[] buf = message.getBytes();
        Sha256Hash hash = Sha256Hash.wrapReversed(Sha256Hash.hashTwice(buf, 0, buf.length));
        byte[] payload = hash.getBytes();

        byte[] bytes = ECIESCoder.encrypt(ecKey.getPubKeyPoint(), payload);
        String verifyHex = Utils.HEX.encode(bytes);
        store .insertAccessPermission(pubKey, Utils.HEX.encode(payload));
        return SessionRandomNumResponse.create(verifyHex);
    }

    public int checkSessionRandomNumResp(String pubKey, String accessToken,BlockStoreInterface store) {
        try {
            return store .getCountAccessPermissionByPubKey(pubKey, accessToken);
        } catch (Exception e) {
            return 0;
        }
    }
}
