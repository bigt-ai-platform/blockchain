/*******************************************************************************
 * <p>
 *  Copyright   2018  Inasset GmbH. 
 *******************************************************************************/
package net.bigtangle.seeds;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import net.bigtangle.core.response.AbstractResponse;
import net.bigtangle.core.response.ErrorResponse;
import net.bigtangle.utils.Gzip;
import net.bigtangle.utils.Json;

@RestController
@RequestMapping("/")
public class DispatcherController {

	private static final Logger logger = LoggerFactory.getLogger(DispatcherController.class);

	public static List<ServerInfo> serverinfoList;
	public static String PATH = "./logs/serverinfo.json";
	@Autowired
	//protected SyncBlockService syncBlockService;

	@SuppressWarnings("unchecked")
	@RequestMapping(value = "{reqCmd}", method = { RequestMethod.POST, RequestMethod.GET })
	public void process(@PathVariable("reqCmd") String reqCmd, @RequestBody byte[] contentBytes,
			HttpServletResponse httpServletResponse, HttpServletRequest httprequest) throws Exception {

		ExecutorService executor = Executors.newSingleThreadExecutor();
		@SuppressWarnings("rawtypes")
		final Future<String> handler = executor.submit((Callable) () -> {
            processDo(reqCmd, contentBytes, httpServletResponse, httprequest);
            return "";
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
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				ServerInfo serverInfo = new ServerInfo();
				String url = (String) request.get("url");
                logger.debug("url=={}", url);
				String servertype = (String) request.get("servertype");
				boolean flag = false;
				if (serverinfoList != null) {
					for (ServerInfo temp : serverinfoList) {
                        if (temp.getUrl().equals(url) && temp.getServertype().equals(servertype)) {
                            flag = true;
                            break;
                        }
					}
				}

				if (!flag) {

					serverInfo.setUrl(url);
					serverInfo.setServertype(servertype);
					serverInfo.setStatus("active");
					if (serverinfoList == null) {
						serverinfoList = new ArrayList<>();
					}
					try {
						SyncBlockService.getMaxConfirmedReward(serverInfo.getUrl());
						serverinfoList.add(serverInfo);
					} catch (Exception e) {
						logger.error("", e);
					}

				}

			}
				break;
			case serverinfolist: {
				List<ServerInfo> tempList = new ArrayList<>();
				if (serverinfoList != null && !serverinfoList.isEmpty()) {
					for (ServerInfo serverInfo : serverinfoList) {
						if (serverInfo.getStatus().equals("active")) {
							tempList.add(serverInfo);
						}
					}
				}

				AbstractResponse response = ServerinfoResponse.create(tempList);
				this.gzipBinary(httpServletResponse, response);

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
                logger.info("{} takes {} from {}", reqCmd, watch.elapsed(TimeUnit.MILLISECONDS), remoteAddr(httprequest));
			watch.stop();
		}
	}

	@RequestMapping("/")
	public String index() {
		return "Bigtangle-seeds";
	}



	private void errorLimit(HttpServletResponse httpServletResponse, Stopwatch watch) throws Exception {
		AbstractResponse resp = ErrorResponse.create(101);
		resp.setErrorcode(403);
		resp.setMessage(" limit reached. ");
		this.outPrintJSONString(httpServletResponse, resp, watch);
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
		String remoteAddr;
		remoteAddr = request.getHeader("X-FORWARDED-FOR");
		if (remoteAddr == null || remoteAddr.isEmpty()) {
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


}
