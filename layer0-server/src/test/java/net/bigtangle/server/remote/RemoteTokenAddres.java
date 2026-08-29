/*******************************************************************************
 *  Copyright   2018  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.server.remote;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Token;
import net.bigtangle.core.UTXO;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetOutputsResponse;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.MonetaryFormat;
import net.bigtangle.utils.OkHttp3Util;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Lists every address that holds the "bc" (BIGTANGLE) token on the server with
 * the summed balance for that address, mirroring
 * {@code WalletService.searchTotalNoSave()}:
 *
 * <ol>
 * <li>POST {@code outputsOfTokenid} with {@code {"tokenid":"bc"}}</li>
 * <li>accumulate {@code utxo.value.value} (raw smallest unit) per address</li>
 * <li>format each total with the token decimals the way
 * {@code new BigDecimal(MonetaryFormat.FIAT.format(total, decimals).trim())}
 * does (BIGTANGLE_DECIMAL = 6, minDecimals 0, trailing zeros trimmed).</li>
 * </ol>
 *
 * The deployed node (p.bigtangle.org:8088, HTTPS) gzips both the request body
 * and the response, so this test gzips the JSON payload before POSTing and
 * gunzips the reply before parsing.
 *
 * Targets the public prod node by default; override with {@code -Dserver.url=...}.
 */
public class RemoteTokenAddres extends RemoteTestBase {

	protected static final Logger log = LoggerFactory.getLogger(RemoteTokenAddres.class);

	public RemoteTokenAddres() {
		contextRoot = System.getProperty("server.url", "https://p.bigtangle.org:8088/");
	}

	private static final byte[] gzipJson(String json) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
			gz.write(json.getBytes(StandardCharsets.UTF_8));
		}
		return bos.toByteArray();
	}

	private static byte[] postGzip(String url, String json) throws IOException {
		RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream"), gzipJson(json));
		Request request = new Request.Builder().url(url).post(body).build();
		try (Response response = OkHttp3Util.getUnsafeOkHttpClient().newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new IOException("HTTP " + response.code() + " from " + url);
			}
			byte[] resp = response.body().bytes();
			if (resp.length > 2 && (resp[0] & 0xff) == 0x1f && (resp[1] & 0xff) == 0x8b) {
				try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(resp))) {
					return gis.readAllBytes();
				}
			}
			return resp;
		}
	}

	@Test
	public void searchTotalNoSave() throws Exception {
		String tokenid = NetworkParameters.BIGTANGLE_TOKENID_STRING;
		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", tokenid);
		byte[] resp = postGzip(contextRoot + ReqCmd.outputsOfTokenid.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetOutputsResponse getOutputsResponse = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
		List<UTXO> outputs = getOutputsResponse.getOutputs();

		Map<String, BigInteger> totalMapValue = new HashMap<String, BigInteger>();
		if (outputs != null && !outputs.isEmpty()) {
			for (UTXO utxo : outputs) {
				String address = utxo.getAddress();
				BigInteger amount = utxo.getValue().getValue();
				BigInteger temp = totalMapValue.get(address);
				totalMapValue.put(address, temp == null ? amount : temp.add(amount));
			}
		}
		assertTrue(!totalMapValue.isEmpty(), "No unspent outputs for token " + tokenid);

		Token t = null;
		try {
			HashMap<String, Object> tokenParam = new HashMap<String, Object>();
			tokenParam.put("tokenid", tokenid);
			byte[] tresp = postGzip(contextRoot + ReqCmd.getTokenById.name(),
					Json.jsonmapper().writeValueAsString(tokenParam));
			GetTokensResponse tokensResponse = Json.jsonmapper().readValue(tresp, GetTokensResponse.class);
			if (tokensResponse.getTokens() != null && !tokensResponse.getTokens().isEmpty()) {
				t = tokensResponse.getTokens().get(0);
			}
		} catch (Exception e) {
			log.warn("could not load token {}: {}", tokenid, e.getMessage());
		}
		int decimals = t == null ? NetworkParameters.BIGTANGLE_DECIMAL : t.getDecimals();

		Map<String, BigDecimal> tokenaddresses = new TreeMap<String, BigDecimal>();
		BigInteger grandTotal = BigInteger.ZERO;
		for (String key : totalMapValue.keySet()) {
			BigDecimal amount = new BigDecimal(MonetaryFormat.FIAT.format(totalMapValue.get(key), decimals).trim());
			tokenaddresses.put(key, amount);
			grandTotal = grandTotal.add(totalMapValue.get(key));
			log.info("address={} amount(raw)={} amount={}", key, totalMapValue.get(key), amount);
		}
		log.info("tokenid={} decimals={} addresses={} grandTotal(raw)={} grandTotal={}", tokenid, decimals,
				tokenaddresses.size(), grandTotal,
				new BigDecimal(MonetaryFormat.FIAT.format(grandTotal, decimals).trim()));

		assertTrue(tokenaddresses.size() > 0, "tokenaddresses should not be empty");
	}

}
