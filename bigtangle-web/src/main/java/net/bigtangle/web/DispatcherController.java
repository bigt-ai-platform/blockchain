/*******************************************************************************
 *  
 *  Copyright   2018  Inasset GmbH. 
 *******************************************************************************/
package net.bigtangle.web;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.base.Stopwatch;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.Utils;
import net.bigtangle.core.response.AbstractResponse;
import net.bigtangle.core.response.ErrorResponse;
import net.bigtangle.utils.Gzip;
import net.bigtangle.utils.Json;
import net.bigtangle.wallet.Wallet;

@RestController
@RequestMapping("/")
public class DispatcherController {

	private static final Logger logger = LoggerFactory.getLogger(DispatcherController.class);

	public static String PATH = "/var/www/";
	public static String CONFPATH = "/etc/apache2/sites-enabled/";

	@Autowired
	protected SyncBlockService syncBlockService;

	@SuppressWarnings("unchecked")
	@RequestMapping(value = "{reqCmd}", method = { RequestMethod.POST, RequestMethod.GET })
	public void process(@PathVariable("reqCmd") String reqCmd, @RequestBody byte[] contentBytes,
			HttpServletResponse httpServletResponse, HttpServletRequest httprequest) throws Exception {

		ExecutorService executor = Executors.newSingleThreadExecutor();
		@SuppressWarnings("rawtypes")
		final Future<String> handler = executor.submit(new Callable() {
			@Override
			public String call() throws Exception {
				processDo(reqCmd, contentBytes, httpServletResponse, httprequest);
				return "";
			}
		});
		try {
			handler.get(30, TimeUnit.MINUTES);
		} catch (TimeoutException e) {
			logger.debug(" process  Timeout  ");
			handler.cancel(true);
			AbstractResponse resp = ErrorResponse.create(100);
			StringWriter sw = new StringWriter();
			resp.setMessage(sw.toString());

		} finally {
			executor.shutdownNow();
		}

	}

	@SuppressWarnings("unchecked")
	public void processDo(@PathVariable("reqCmd") String reqCmd, @RequestBody byte[] contentBytes,
			HttpServletResponse httpServletResponse, HttpServletRequest httprequest) throws Exception {
		Stopwatch watch = Stopwatch.createStarted();

		String backMessage = "";
		byte[] bodyByte = new byte[0];
		try {

			logger.trace("reqCmd : {} from {}, size : {}, started.", reqCmd, httprequest.getRemoteAddr(),
					contentBytes.length);

			bodyByte = Gzip.decompressOut(contentBytes);
			ReqCmd reqCmd0000 = ReqCmd.valueOf(reqCmd);

			switch (reqCmd0000) {

			case register: {

			}
				break;

			default:
				break;
			}
		} catch (Throwable exception) {
			logger.error("reqCmd : {}, reqHex : {}, {},error.", reqCmd, bodyByte.length, remoteAddr(httprequest),
					exception);
			AbstractResponse resp = ErrorResponse.create(100);
			StringWriter sw = new StringWriter();
			sw.append(backMessage);
			exception.printStackTrace(new PrintWriter(sw));
			resp.setMessage(sw.toString());
			this.outPrintJSONString(httpServletResponse, resp, watch);
		} finally {
			if (watch.elapsed(TimeUnit.MILLISECONDS) > 1000)
				logger.info(reqCmd + " takes {} from {}", watch.elapsed(TimeUnit.MILLISECONDS),
						remoteAddr(httprequest));
			watch.stop();
		}
	}

	@RequestMapping("/")
	public String index() {
		return "Bigtangle-web";
	}

	public Block createToken(ECKey key, String tokename, int decimals, String domainname, String description,
			BigInteger amount, boolean increment, TokenKeyValues tokenKeyValues, int tokentype, String tokenid,
			Wallet w) throws Exception {
		w.importKey(key);
		Token token = Token.buildSimpleTokenInfo(true, Sha256Hash.ZERO_HASH, tokenid, tokename, description, 1, 0,
				amount, !increment, decimals, "");
		token.setTokenKeyValues(tokenKeyValues);
		token.setTokentype(tokentype);
		List<MultiSignAddress> addresses = new ArrayList<MultiSignAddress>();
		addresses.add(new MultiSignAddress(tokenid, "", key.getPublicKeyAsHex()));
		return w.createToken(key, domainname, increment, token, addresses);

	}

	private void errorLimit(HttpServletResponse httpServletResponse, Stopwatch watch) throws Exception {
		AbstractResponse resp = ErrorResponse.create(101);
		resp.setErrorcode(403);
		resp.setMessage(" limit reached. ");
		this.outPrintJSONString(httpServletResponse, resp, watch);
	}

	public void outPutDataMap(HttpServletResponse httpServletResponse, Object data) throws Exception {
		httpServletResponse.setCharacterEncoding("UTF-8");
		HashMap<String, Object> result = new HashMap<String, Object>();
		result.put("data", data);
		GZIPOutputStream servletOutputStream = new GZIPOutputStream(httpServletResponse.getOutputStream());

		servletOutputStream.write(Json.jsonmapper().writeValueAsBytes(result));
		servletOutputStream.flush();
		servletOutputStream.close();
	}

	public void outPointBinaryArray(HttpServletResponse httpServletResponse, byte[] data) throws Exception {
		httpServletResponse.setCharacterEncoding("UTF-8");

		HashMap<String, Object> result = new HashMap<String, Object>();
		result.put("dataHex", Utils.HEX.encode(data));
		GZIPOutputStream servletOutputStream = new GZIPOutputStream(httpServletResponse.getOutputStream());

		servletOutputStream.write(Json.jsonmapper().writeValueAsBytes(result));
		servletOutputStream.flush();
		servletOutputStream.close();
	}

	public void outPrintJSONString(HttpServletResponse httpServletResponse, AbstractResponse response, Stopwatch watch)
			throws Exception {
		long duration = watch.elapsed(TimeUnit.MILLISECONDS);
		response.setDuration(duration);
		gzipBinary(httpServletResponse, response);
	}

	public void gzipBinary(HttpServletResponse httpServletResponse, AbstractResponse response) throws Exception {
		GZIPOutputStream servletOutputStream = new GZIPOutputStream(httpServletResponse.getOutputStream());

		servletOutputStream.write(Json.jsonmapper().writeValueAsBytes(response));
		servletOutputStream.flush();
		servletOutputStream.close();
	}

	public String remoteAddr(HttpServletRequest request) {
		String remoteAddr = "";
		remoteAddr = request.getHeader("X-FORWARDED-FOR");
		if (remoteAddr == null || "".equals(remoteAddr)) {
			remoteAddr = request.getRemoteAddr();
		} else {
			StringTokenizer tokenizer = new StringTokenizer(remoteAddr, ",");
            if (tokenizer.hasMoreTokens()) {
                do {
                    remoteAddr = tokenizer.nextToken();
                    break;
                } while (tokenizer.hasMoreTokens());
            }
		}
		return remoteAddr;
	}

	private static final String Huobi15Fee = "15";
}
