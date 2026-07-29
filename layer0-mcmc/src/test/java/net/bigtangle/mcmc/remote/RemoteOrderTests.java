package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Utils;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetOutputsResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

public class RemoteOrderTests extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteOrderTests.class);

    @Test
    public void testCreateTokenAndCheckOutputs() throws Exception {
        PQKey issuer = PQKey.createNew();
        String tokenName = "diagtoken";
        BigInteger supply = BigInteger.valueOf(10000000L);

        Block block = createToken(issuer, tokenName, 0, "", "token for diag",
                supply, true, null,
                TokenType.token.ordinal(), issuer.getPublicKeyAsHex(), wallet);
        assertNotNull(block, "createToken should return a block");

        Block signed = wallet.multiSign(issuer.getPublicKeyAsHex(),
                wallet.walletKeys().get(0), aesKey);
        if (signed != null) {
            makeRewardBlock(signed);
        }

        String tokenId = issuer.getPublicKeyAsHex();
        Token foundToken = null;
        for (int i = 0; i < 20; i++) {
            foundToken = getToken(tokenId);
            if (foundToken != null) break;
            if (i < 19) Thread.sleep(3000);
        }
        assertNotNull(foundToken, "Token should exist via checkTokenId");
        log.info("Token {} created, id={}", tokenName, tokenId);
        Thread.sleep(15000);

        String issuerPkh = Utils.HEX.encode(issuer.getPubKeyHash());
        log.info("Issuer pubKeyHash hex: {}", issuerPkh);

        byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.getOutputs.name(),
                Json.jsonmapper().writeValueAsString(List.of(issuerPkh)).getBytes());
        GetOutputsResponse outputResp = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
        log.info("getOutputs for issuer returned {} UTXOs", outputResp.getOutputs().size());

        String genesisPkh = Utils.HEX.encode(wallet.walletKeys().get(0).getPubKeyHash());
        resp = OkHttp3Util.post(contextRoot + ReqCmd.getOutputs.name(),
                Json.jsonmapper().writeValueAsString(List.of(genesisPkh)).getBytes());
        outputResp = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
        log.info("getOutputs for genesis returned {} UTXOs", outputResp.getOutputs().size());

        log.info("DIAGNOSTIC: Check l0-server.log for 'getStoredOutputs: no outputs' to see the computed address");
    }

    private Token getToken(String idcom) throws Exception {
        try {
            return wallet.checkTokenId(idcom);
        } catch (Exception e) {
            return null;
        }
    }
}
