package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;

public class RemoteOrderTests extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteOrderTests.class);

    @Test
    public void testCreateTokenForOrders() throws Exception {
        PQKey issuer = PQKey.createNew();
        String tokenName = "ordertoken";
        BigInteger supply = BigInteger.valueOf(10000000L);

        Block block = createToken(issuer, tokenName, 0, "", "token for order test",
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
        assertNotNull(foundToken, "Token " + tokenId + " should exist via checkTokenId");
        log.info("Token {} created and confirmed, id={}", tokenName, tokenId);
    }

    private Token getToken(String idcom) throws Exception {
        try {
            return wallet.checkTokenId(idcom);
        } catch (Exception e) {
            return null;
        }
    }
}
