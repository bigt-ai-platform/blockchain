package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.ECKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.ECIESCoder;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.SessionRandomNumResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

 
public class AccessPermissionedTest extends AbstractIntegrationTest {

    @Test
    public void checkAccessPermissionedPubKeySign() throws Exception {
        ECKey ecKey = addAccessGrant();
        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        requestParam.put("pubKey", ecKey.getPublicKeyAsHex());
       byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.getSessionRandomNum.name(),
                Json.jsonmapper().writeValueAsBytes(requestParam));
        SessionRandomNumResponse sessionRandomNumResponse = Json.jsonmapper().readValue(resp,
                SessionRandomNumResponse.class);

        // eckey sign
        byte[] payload = Utils.HEX.decode(sessionRandomNumResponse.getVerifyHex());
        byte[] decryptedPayload = ECIESCoder.decrypt(ecKey.getPrivKey(), payload);
        final String accessToken = Utils.HEX.encode(decryptedPayload);
        System.out.println("accessToken : " + accessToken);

        Sha256Hash hash = Sha256Hash.wrap(decryptedPayload);
        ECKey.ECDSASignature party1Signature = ecKey.sign(hash);
        byte[] signature = party1Signature.encodeToDER();

        System.out.println("pubKey : " + ecKey.getPublicKeyAsHex());
        System.out.println("signature : " + Utils.HEX.encode(signature));
        System.out.println("accessToken : " + Utils.HEX.encode(hash.getBytes()));

        final String header = ecKey.getPublicKeyAsHex() + "," + Utils.HEX.encode(signature) + ","
                + Utils.HEX.encode(hash.getBytes());
        HashMap<String, String> requestParam00 = new HashMap<String, String>();
        requestParam00.put("name", null);
        resp = OkHttp3Util.post(contextRoot + ReqCmd.searchTokens.name(),
                Json.jsonmapper().writeValueAsString(requestParam00).getBytes(), header);
        System.out.println(resp);
    }

    @Test
    public void checkAccessPermissionedPubKeySignNoAuth() throws Exception {
        ECKey ecKey = new ECKey();
        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        requestParam.put("pubKey", ecKey.getPublicKeyAsHex());
       byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.getSessionRandomNum.name(),
                Json.jsonmapper().writeValueAsBytes(requestParam));
        SessionRandomNumResponse sessionRandomNumResponse = Json.jsonmapper().readValue(resp,
                SessionRandomNumResponse.class);

        // eckey sign
        byte[] payload = Utils.HEX.decode(sessionRandomNumResponse.getVerifyHex());
        byte[] decryptedPayload = ECIESCoder.decrypt(ecKey.getPrivKey(), payload);
        final String accessToken = Utils.HEX.encode(decryptedPayload);
        System.out.println("accessToken : " + accessToken);

        Sha256Hash hash = Sha256Hash.wrap(decryptedPayload);
        ECKey.ECDSASignature party1Signature = ecKey.sign(hash);
        byte[] signature = party1Signature.encodeToDER();

        System.out.println("pubKey : " + ecKey.getPublicKeyAsHex());
        System.out.println("signature : " + Utils.HEX.encode(signature));
        System.out.println("accessToken : " + Utils.HEX.encode(hash.getBytes()));

        final String header = ecKey.getPublicKeyAsHex() + "," + Utils.HEX.encode(signature) + ","
                + Utils.HEX.encode(hash.getBytes());
        HashMap<String, String> requestParam00 = new HashMap<String, String>();
        requestParam00.put("name", null);
        try {
            OkHttp3Util.post(contextRoot + ReqCmd.searchTokens.name(),
                    Json.jsonmapper().writeValueAsString(requestParam00).getBytes(), header);
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().endsWith("no auth"));
        }
    }
    
    @Test
    public void deleteAccessGrant() throws Exception {
        ECKey ecKey = this.addAccessGrant();

        ECKey ecKey0 = ECKey.fromPublicOnly(Utils.HEX.decode(testPub));
        final String header = ecKey0.getPublicKeyAsHex();
        
        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        requestParam.put("pubKey", ecKey.getPublicKeyAsHex());
        OkHttp3Util.post(contextRoot + ReqCmd.deleteAccessGrant.name(),
                Json.jsonmapper().writeValueAsBytes(requestParam), header);
    }

    public ECKey addAccessGrant() throws Exception {
        ECKey ecKey0 = ECKey.fromPublicOnly(Utils.HEX.decode(testPub));
        final String header = ecKey0.getPublicKeyAsHex();
        
        ECKey ecKey = new ECKey();
        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        requestParam.put("pubKey", ecKey.getPublicKeyAsHex());
        OkHttp3Util.post(contextRoot + ReqCmd.addAccessGrant.name(), Json.jsonmapper().writeValueAsBytes(requestParam), header);
        return ecKey;
    }

    @Test
    public void checkPermissionadmin() throws Exception {
        ECKey ecKey = ECKey.fromPublicOnly(Utils.HEX.decode(testPub));
        final String header = ecKey.getPublicKeyAsHex();
        HashMap<String, String> requestParam00 = new HashMap<String, String>();
        requestParam00.put("name", null);
       byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.searchTokens.name(),
                Json.jsonmapper().writeValueAsString(requestParam00).getBytes(), header);
        System.out.println(resp);
    }
    
    @Test
    public void checkAccessGrantNoAuth() throws Exception {
        ECKey ecKey = new ECKey();
        final String header = ecKey.getPublicKeyAsHex();
        HashMap<String, String> requestParam00 = new HashMap<String, String>();
        requestParam00.put("name", null);
        try {
        OkHttp3Util.post(contextRoot + ReqCmd.searchTokens.name(),
                Json.jsonmapper().writeValueAsString(requestParam00).getBytes(), header);
        }catch (RuntimeException e) {
            assertTrue(e.getMessage().endsWith("no auth"));
        }
    }
}
