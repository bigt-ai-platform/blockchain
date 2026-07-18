package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.TestParams;

public class TokenSerializationTest {

    private final NetworkParameters params = TestParams.get();

    @Test
    public void testRoundTripFull() throws Exception {
        Token original = new Token();
        original.setTokenid("test_token_id_123");
        original.setTokenindex(42);
        original.setTokenname("TestCoin");
        original.setDescription("A test token for unit testing");
        original.setDomainName("testdomain");
        original.setDomainNameBlockHash("domainblockhash123");
        original.setSignnumber(2);
        original.setTokentype(TokenType.token.ordinal());
        original.setTokenstop(false);
        original.setPrevblockhash(Sha256Hash.of("prev".getBytes()));
        original.setAmount(BigInteger.valueOf(1000000));
        original.setDecimals(8);
        original.setClassification("currency");
        original.setLanguage("en");
        original.setRevoked(false);
        original.setBlockHash(Sha256Hash.of("block".getBytes()));
        original.setConfirmed(true);
        original.setSpent(false);
        original.setTime(1234567890L);

        byte[] bytes = original.toByteArray();
        Token reparsed = new Token().parse(bytes);

        assertEquals(original.getTokenid(), reparsed.getTokenid());
        assertEquals(original.getTokenindex(), reparsed.getTokenindex());
        assertEquals(original.getTokenname(), reparsed.getTokenname());
        assertEquals(original.getDescription(), reparsed.getDescription());
        assertEquals(original.getDomainName(), reparsed.getDomainName());
        assertEquals(original.getDomainNameBlockHash(), reparsed.getDomainNameBlockHash());
        assertEquals(original.getSignnumber(), reparsed.getSignnumber());
        assertEquals(original.getTokentype(), reparsed.getTokentype());
        assertEquals(original.isTokenstop(), reparsed.isTokenstop());
        assertEquals(original.getPrevblockhash(), reparsed.getPrevblockhash());
        assertEquals(original.getAmount(), reparsed.getAmount());
        assertEquals(original.getDecimals(), reparsed.getDecimals());
        assertEquals(original.getClassification(), reparsed.getClassification());
        assertEquals(original.getLanguage(), reparsed.getLanguage());
        assertEquals(original.getRevoked(), reparsed.getRevoked());
        assertEquals(original.getBlockHash(), reparsed.getBlockHash());
        assertEquals(original.isConfirmed(), reparsed.isConfirmed());
        assertEquals(original.isSpent(), reparsed.isSpent());
        assertEquals(original.getTime(), reparsed.getTime());
    }

    @Test
    public void testRoundTripWithTokenKeyValues() throws Exception {
        Token original = new Token();
        original.setTokenid("kv_token");
        original.setTokenname("KVToken");
        original.setAmount(BigInteger.valueOf(500));
        original.setTokentype(TokenType.token.ordinal());
        original.setSignnumber(1);
        original.setBlockHash(Sha256Hash.of("kvblock".getBytes()));
        original.setConfirmed(true);

        TokenKeyValues tkv = new TokenKeyValues();
        KeyValue kv1 = new KeyValue(); kv1.setKey("url"); kv1.setValue("https://example.com"); tkv.addKeyvalue(kv1);
        KeyValue kv2 = new KeyValue(); kv2.setKey("icon"); kv2.setValue("data:image/png;base64,abc123"); tkv.addKeyvalue(kv2);
        original.setTokenKeyValues(tkv);

        byte[] bytes = original.toByteArray();
        Token reparsed = new Token().parse(bytes);

        assertEquals(original.getTokenid(), reparsed.getTokenid());
        assertEquals(original.getTokenname(), reparsed.getTokenname());
        assertNotNull(reparsed.getTokenKeyValues());
        assertEquals(2, reparsed.getTokenKeyValues().getKeyvalues().size());
        assertEquals("url", reparsed.getTokenKeyValues().getKeyvalues().get(0).getKey());
        assertEquals("https://example.com", reparsed.getTokenKeyValues().getKeyvalues().get(0).getValue());
        assertEquals("icon", reparsed.getTokenKeyValues().getKeyvalues().get(1).getKey());
    }

    @Test
    public void testRoundTripGenesisToken() throws Exception {
        Token original = Token.genesisToken(params);
        byte[] bytes = original.toByteArray();
        Token reparsed = new Token().parse(bytes);

        assertEquals(original.getTokenid(), reparsed.getTokenid());
        assertEquals(original.getTokenname(), reparsed.getTokenname());
        assertEquals(original.getAmount(), reparsed.getAmount());
        assertEquals(original.getDecimals(), reparsed.getDecimals());
        assertEquals(original.getTokentype(), reparsed.getTokentype());
        assertEquals(original.getSignnumber(), reparsed.getSignnumber());
        assertEquals(original.isTokenstop(), reparsed.isTokenstop());
        assertEquals(original.getBlockHash(), reparsed.getBlockHash());
        assertTrue(reparsed.isConfirmed());
    }

    @Test
    public void testRoundTripDomainnameToken() throws Exception {
        Token original = Token.buildDomainnameTokenInfo(true,
                Sha256Hash.of("domainprev".getBytes()),
                "domain_token_1", "example.bigtangle", "Example Domain",
                1, 0, true, "example.bigtangle", "prevdomainhash");

        byte[] bytes = original.toByteArray();
        Token reparsed = new Token().parse(bytes);

        assertEquals(original.getTokenid(), reparsed.getTokenid());
        assertEquals(original.getTokenname(), reparsed.getTokenname());
        assertEquals(original.getDomainName(), reparsed.getDomainName());
        assertEquals(original.getTokentype(), reparsed.getTokentype());
        assertTrue(reparsed.isTokenDomainname());
        assertTrue(reparsed.isTokenstop());
    }

    @Test
    public void testRoundTripSubtangleToken() throws Exception {
        Token original = Token.buildSubtangleTokenInfo(true,
                Sha256Hash.of("subprev".getBytes()),
                "subtangle_1", "SubTangle", "A subtangle chain",
                "subtangle.bigtangle");

        byte[] bytes = original.toByteArray();
        Token reparsed = new Token().parse(bytes);

        assertEquals(original.getTokenid(), reparsed.getTokenid());
        assertEquals(original.getTokenname(), reparsed.getTokenname());
        assertEquals(original.getDomainName(), reparsed.getDomainName());
        assertEquals(original.getTokentype(), reparsed.getTokentype());
        assertTrue(reparsed.isTokenstop());
        assertEquals(BigInteger.ZERO, reparsed.getAmount());
    }

    @Test
    public void testRoundTripMinimal() throws Exception {
        Token original = new Token();
        original.setTokenid("minimal");
        original.setTokenname("Min");
        original.setAmount(BigInteger.ONE);
        original.setBlockHash(Sha256Hash.ZERO_HASH);

        byte[] bytes = original.toByteArray();
        Token reparsed = new Token().parse(bytes);

        assertEquals("minimal", reparsed.getTokenid());
        assertEquals("Min", reparsed.getTokenname());
        assertEquals(BigInteger.ONE, reparsed.getAmount());
    }

    @Test
    public void testDeterministic() throws Exception {
        Token a = buildSample();
        Token b = buildSample();
        byte[] ba = a.toByteArray();
        byte[] bb = b.toByteArray();
        assertEquals(ba.length, bb.length);
        assertArrayEquals(ba, bb);
    }

    @Test
    public void testNullFields() throws Exception {
        Token original = new Token();
        original.setTokenid("null_test");
        original.setTokenname("NullTest");
        original.setAmount(BigInteger.TEN);
        original.setBlockHash(Sha256Hash.of("b".getBytes()));
        // Leave most fields null/default

        byte[] bytes = original.toByteArray();
        Token reparsed = new Token().parse(bytes);

        assertEquals("null_test", reparsed.getTokenid());
        assertEquals("NullTest", reparsed.getTokenname());
        assertEquals(BigInteger.TEN, reparsed.getAmount());
        assertEquals(0, reparsed.getTokenindex());
        assertEquals(0, reparsed.getDecimals());
        assertEquals(null, reparsed.getPrevblockhash());
    }

    @Test
    public void testRevokedTrue() throws Exception {
        Token original = new Token();
        original.setTokenid("revoked");
        original.setTokenname("RevokedToken");
        original.setAmount(BigInteger.valueOf(100));
        original.setRevoked(true);
        original.setBlockHash(Sha256Hash.of("r".getBytes()));

        byte[] bytes = original.toByteArray();
        Token reparsed = new Token().parse(bytes);

        assertTrue(reparsed.getRevoked());
    }

    @Test
    public void testBigIntegerAmount() throws Exception {
        Token original = new Token();
        original.setTokenid("big_amount");
        original.setTokenname("BigAmount");
        original.setAmount(new BigInteger("999999999999999999999999999999999999"));
        original.setBlockHash(Sha256Hash.of("big".getBytes()));

        byte[] bytes = original.toByteArray();
        Token reparsed = new Token().parse(bytes);

        assertEquals(original.getAmount(), reparsed.getAmount());
    }

    private static Token buildSample() {
        Token t = new Token();
        t.setTokenid("sample_token");
        t.setTokenindex(1);
        t.setTokenname("Sample");
        t.setDescription("Sample description");
        t.setDomainName("sample.bigtangle");
        t.setDomainNameBlockHash("sampleblockhash");
        t.setSignnumber(1);
        t.setTokentype(TokenType.token.ordinal());
        t.setTokenstop(false);
        t.setPrevblockhash(Sha256Hash.of("prevhash".getBytes()));
        t.setAmount(BigInteger.valueOf(10000));
        t.setDecimals(4);
        t.setClassification("test");
        t.setLanguage("en");
        t.setRevoked(false);
        t.setBlockHash(Sha256Hash.of("blockhash".getBytes()));
        t.setConfirmed(true);
        t.setSpent(false);
        t.setTime(1000000L);
        return t;
    }
}
