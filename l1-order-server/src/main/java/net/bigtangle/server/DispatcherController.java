/*******************************************************************************
 * <p>
 *  Copyright   2018  Inasset GmbH. 
 *******************************************************************************/
package net.bigtangle.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.DisposableBean;

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
import jakarta.servlet.http.HttpSession;
import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.ErrorResponse;
import net.bigtangle.response.GetBlockListResponse;
import net.bigtangle.response.GetStringResponse;
import net.bigtangle.response.OkResponse;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.AccessGrantService;
import net.bigtangle.server.service.AccessPermissionedService;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.BlockService;
import net.bigtangle.server.service.BlockServiceCreate;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.server.service.MempoolService;
import net.bigtangle.layer1.service.MultiSignService;
import net.bigtangle.layer1.service.MultiSignServiceCreate;
import net.bigtangle.layer1.service.OutputService;
import net.bigtangle.layer1.service.OrderTickerService;
import net.bigtangle.layer1.service.OrderdataService;
import net.bigtangle.layer1.service.TokenDomainnameService;
import net.bigtangle.layer1.service.TokensService;
 
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.SubtanglePermissionService;
import net.bigtangle.server.service.UserDataService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

@RestController
@RequestMapping("/")
public class DispatcherController implements DisposableBean {

	private static final Logger logger = LoggerFactory.getLogger(DispatcherController.class);

	private ExecutorService requestExecutor = Executors.newFixedThreadPool(
			Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

	@Autowired
	private NetworkParameters networkParameters;
	@Autowired
	private UserDataService userDataService;
	@Autowired
	private OutputService walletService;
	@Autowired
	private BlockService blockService;
	@Autowired
	private BlockServiceCreate blockServiceCreate;
	@Autowired
	private BlockSaveService blockSaveService;
	@Autowired
	private SubtanglePermissionService subtanglePermissionService;
	@Autowired
	private OrderdataService orderdataService;
	@Autowired
	ServerConfiguration serverConfiguration;
	@Autowired
	private OrderTickerService orderTickerService;
	@Autowired
	private TokenDomainnameService tokenDomainnameService;
	@Autowired
	private MultiSignService multiSignService;
	@Autowired
	private MultiSignServiceCreate multiSignServiceCreate;
	@Autowired
	private TokensService tokensService;
	@Autowired
	protected StoreService storeService;

	 
	@Autowired
	private AccessPermissionedService accessPermissionedService;
	@Autowired
	private AccessGrantService accessGrantService;
	@Autowired
	protected CacheBlockPrototypeService cacheBlockPrototypeService;
	@Autowired
	private MempoolService mempoolService;

	@Override
	public void destroy() {
		requestExecutor.shutdownNow();
	}

	@SuppressWarnings("unchecked")
	@RequestMapping(value = "{reqCmd}", method = { RequestMethod.POST, RequestMethod.GET })
	public void process(@PathVariable("reqCmd") String reqCmd, @RequestBody byte[] contentBytes,
			HttpServletResponse httpServletResponse, HttpServletRequest httprequest) throws Exception {
		userDataService.addStatistcs(reqCmd, remoteAddr(httprequest));
		if (!userDataService.ipCheck(reqCmd, contentBytes, httprequest)) {
			Stopwatch watch = Stopwatch.createStarted();
            errorLimit(httpServletResponse, watch);
			return;
        }
		@SuppressWarnings("rawtypes")
		final Future<String> handler = requestExecutor.submit((Callable) () -> {
			try {
				processDo(reqCmd, contentBytes, httpServletResponse, httprequest);
			} catch (Throwable t) {
				logger.error("ERROR in processDo reqCmd={}: {}: {}", reqCmd, t.getClass().getName(), t.getMessage());
				logger.error("Error processing request", t);
				throw t;
			}
            return "";
        });
		try {
			handler.get(serverConfiguration.getTimeoutMinute(), TimeUnit.MINUTES);
		} catch (TimeoutException e) {
			logger.debug(" process  Timeout  ");
			handler.cancel(true);
			AbstractResponse resp = ErrorResponse.create(100);
			StringWriter sw = new StringWriter();
			resp.setMessage(sw.toString());
			gzipBinary(httpServletResponse, resp, reqCmd);
		} catch (java.util.concurrent.ExecutionException e) {
			logger.error("ERROR ExecutionException reqCmd={} cause={}", reqCmd, e.getCause() != null ? e.getCause().getClass().getName() + ": " + e.getCause().getMessage() : "null");
			logger.error("process ExecutionException for reqCmd={}", reqCmd, e.getCause());
			Stopwatch watch = Stopwatch.createStarted();
			AbstractResponse resp = ErrorResponse.create(101);
			resp.setMessage(e.getCause() != null ? e.getCause().getLocalizedMessage() : e.getLocalizedMessage());
			this.outPrintJSONString(httpServletResponse, resp, watch, reqCmd);
		}

	}

	@SuppressWarnings("unchecked")
	public void processDo(@PathVariable("reqCmd") String reqCmd, @RequestBody byte[] contentBytes,
			HttpServletResponse httpServletResponse, HttpServletRequest httprequest) throws Exception {
		Stopwatch watch = Stopwatch.createStarted();
		BlockStoreInterface store = storeService.getStore();
		byte[] bodyByte = new byte[0];
		try {

			logger.trace("reqCmd : {} from {}, size : {}, started.", reqCmd, httprequest.getRemoteAddr(),
					contentBytes.length);

			bodyByte = contentBytes;
			ReqCmd reqCmd0000 = ReqCmd.valueOf(reqCmd);
			if (!checkPermission(httpServletResponse, httprequest, watch, store)) {
				return;
			}
			if (!checkReady(httpServletResponse, watch)) {
				return;
			}
			switch (reqCmd0000) {
			case getTip: {
				Block rollingBlock = cacheBlockPrototypeService.getBlockPrototype(store);
				if (!userDataService.ipCheck(reqCmd, contentBytes, httprequest)) {
					// return bomb
                    logger.debug("bomb getDifficultyTarget {} {}", remoteAddr(httprequest), reqCmd);
					errorLimit(httpServletResponse, watch);
					return;
                }

				byte[] data = rollingBlock.bitcoinSerialize();
				this.outPointBinaryArray(httpServletResponse, data, reqCmd);
			}
				break;
			case batchBlock: {
				batchBlock(bodyByte, httpServletResponse, watch, store);
			}
				break;
			case submitTransaction: {
				Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(bodyByte);
				mempoolService.submitTransaction(tx);
				blockSaveService.broadcastTransaction(tx);
				this.outPrintJSONString(httpServletResponse, new OkResponse(), watch, reqCmd);
			}
				break;
			case submitTransactions: {
				java.io.DataInputStream dis = new java.io.DataInputStream(
						new java.io.ByteArrayInputStream(bodyByte));
				int count = 0;
				while (dis.available() > 0) {
					int len = dis.readInt();
					byte[] txBytes = new byte[len];
					dis.readFully(txBytes);
					Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(txBytes);
					mempoolService.submitTransaction(tx);
				blockSaveService.broadcastTransaction(tx);
					count++;
				}
				GetStringResponse resp = new GetStringResponse();
				resp.setMessage(String.valueOf(count));
				this.outPrintJSONString(httpServletResponse, resp, watch, reqCmd);
			}
				break;
			case getOutputs: {
				if (!userDataService.ipCheck(reqCmd, contentBytes, httprequest)) {
                    logger.debug("getOutputs denied {} {}", remoteAddr(httprequest), reqCmd);
					errorLimit(httpServletResponse, watch);
					return;
				}
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				List<String> keyStrHex000 = Json.jsonmapper().readValue(reqStr, List.class);
				Set<byte[]> pubKeyHashs = new HashSet<>();
				for (String keyStrHex : keyStrHex000) {
					pubKeyHashs.add(Utils.HEX.decode(keyStrHex));
				}
				AbstractResponse response = walletService.getAccountOutputs(pubKeyHashs, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case outputsOfTokenid: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				AbstractResponse response = walletService.getOpenAllOutputsResponse((String) request.get("tokenid"),
						store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getBalances: {
				if (!userDataService.ipCheck(reqCmd, contentBytes, httprequest)) {
                    logger.debug("getOutputs getBalances {} {}", remoteAddr(httprequest), reqCmd);
					return;
				}
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				List<String> keyStrHex000 = Json.jsonmapper().readValue(reqStr, List.class);
				Set<byte[]> pubKeyHashs = new HashSet<>();
				for (String keyStrHex : keyStrHex000) {
					pubKeyHashs.add(Utils.HEX.decode(keyStrHex));
				}
				AbstractResponse response = walletService.getAccountBalanceInfo(pubKeyHashs, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getAccountBalances: {
				if (!userDataService.ipCheck(reqCmd, contentBytes, httprequest)) {
                    logger.debug("getOutputs getBalances {} {}", remoteAddr(httprequest), reqCmd);
					return;
				}
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				List<String> keyStrHex000 = Json.jsonmapper().readValue(reqStr, List.class);
				Set<byte[]> pubKeyHashs = new HashSet<>();
				for (String keyStrHex : keyStrHex000) {
					pubKeyHashs.add(Utils.HEX.decode(keyStrHex));
				}
				AbstractResponse response = walletService.getAccountBalanceInfoFromAccount(pubKeyHashs, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case findBlockEvaluation: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				AbstractResponse response = this.blockService.searchBlock(request, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;

			case searchBlockByBlockHashs: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				AbstractResponse response = this.blockService.searchBlockByBlockHashs(request, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;

			case getBlockByHash: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				if (request.get("hashHex") != null) {
					Block block = this.blockService.getBlock(Sha256Hash.wrap(request.get("hashHex").toString()), store);
					if (block != null) {
						if ("true".equals(request.get("text"))) {
							this.outPrintJSONString(httpServletResponse, GetStringResponse.create(block.toString()),
									watch, reqCmd);
						} else {
							this.outPointBinaryArray(httpServletResponse, block.bitcoinSerialize(), reqCmd);
						}
					} else {
						throw new NoBlockException();
					}
				} else {
					throw new NoBlockException();
				}
			}
				break;
			case adjustHeight: {
				Block block = networkParameters.getDefaultSerializer().makeBlock(bodyByte);
				this.blockServiceCreate.adjustHeightRequiredBlocks(block, store);
				this.outPointBinaryArray(httpServletResponse, block.bitcoinSerialize(), reqCmd);
			}
				break;

			case blocksFromChainLength: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				GetBlockListResponse response = this.blockService.blocksFromChainLength(
						Long.valueOf((String) request.get("start")), Long.valueOf((String) request.get("end")), store);

				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case blocksFromNonChainHeight: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				long cutoffHeight = Long.parseLong(
						request.get("cutoffHeight") == null ? "1" : (String) request.get("cutoffHeight"));
				GetBlockListResponse response = this.blockService.blocksFromNonChainHeigth(cutoffHeight, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getUserData: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String dataclassname = (String) request.get("dataclassname");
				String pubKey = (String) request.get("pubKey");
				byte[] buf = this.userDataService.getUserData(dataclassname, pubKey, store);
				this.outPointBinaryArray(httpServletResponse, buf, reqCmd);
			}
				break;
			case userDataList: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				int blocktype = (int) request.get("blocktype");
				List<String> pubKeyList = (List<String>) request.get("pubKeyList");
				AbstractResponse response = this.userDataService.getUserDataList(blocktype, pubKeyList, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case regSubtangle: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String pubkey = (String) request.get("pubkey");
				String signHex = (String) request.get("signHex");
				boolean flag = subtanglePermissionService.savePubkey(pubkey, signHex, store);
				if (flag) {
					this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
				} else {
					this.outPrintJSONString(httpServletResponse, ErrorResponse.create(0), watch, reqCmd);
				}
			}
				break;
			case updateSubtangle: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String pubkey = (String) request.get("pubkey");
				String userdataPubkey = (String) request.get("userdataPubkey");
				String status = (String) request.get("status");
				subtanglePermissionService.updateSubtanglePermission(pubkey, "", userdataPubkey, status, store);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;
			case getTokenSignByAddress: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String address = (String) request.get("address");
				String tokenid = (String) request.get("tokenid");
				AbstractResponse response = this.multiSignService.getMultiSignListWithAddress(tokenid, address, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getTokenSigns: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String tokenid = (String) request.get("tokenid");
				long tokenindex = Long.parseLong(request.get("tokenindex") + "");
				int sign = Integer.parseInt(request.get("sign") + "");
				AbstractResponse response = this.multiSignService.getCountMultiSign(tokenid, tokenindex, sign, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getTokenSignByTokenid: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String tokenid = (String) request.get("tokenid");
				String tokenindex = (String) request.get("tokenindex");
				if (tokenindex == null || tokenindex.trim().isEmpty()) {
					tokenindex = "-1";
				}
				Boolean isSign = (Boolean) request.get("isSign");
				AbstractResponse response = this.multiSignService.getMultiSignListWithTokenid(tokenid,
						Integer.valueOf(tokenindex), (List<String>) request.get("addresses"), isSign != null && isSign,
						store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;

			case signToken: {
				Block block = networkParameters.getDefaultSerializer().makeBlock(bodyByte);
				if (!networkParameters.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_TOKEN_CREATION)) {
					throw new VerificationException(
							"Token creation is not allowed on chain " + networkParameters.getChainId());
				}
				this.multiSignServiceCreate.signTokenAndSaveBlock(block, store);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;

			case getTokenIndex: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String tokenid = (String) request.get("tokenid");
				AbstractResponse response = this.multiSignService.getNextTokenSerialIndex(tokenid, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getTokenById: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				AbstractResponse response = tokensService.getTokenById((String) request.get("tokenid"), store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getTokenPermissionedAddresses: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				final String domainNameBlockHash = (String) request.get("domainNameBlockHash");
				AbstractResponse response = this.tokenDomainnameService
						.queryDomainnameTokenPermissionedAddresses(domainNameBlockHash, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getDomainNameBlockHash: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				final String domainname = (String) request.get("domainname");
				final String token = (String) request.get("token");
				if (token == null || token.isEmpty()) {
					this.outPrintJSONString(httpServletResponse,
							this.tokenDomainnameService.queryParentDomainnameBlockHash(domainname, store), watch, reqCmd);
				} else {
					this.outPrintJSONString(httpServletResponse,
							this.tokenDomainnameService.queryDomainnameBlockHash(domainname, store), watch, reqCmd);
				}
			}
				break;
			case getOrders: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
                String address = (String) request.get("address");
				String tokenid = (String) request.get("tokenid");
                List<String> addresses = (List<String>) request.get("addresses");
				AbstractResponse response = orderdataService.getOrderdataList(address, addresses, tokenid,
						store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getOrdersTicker: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				Long startDate = (Long) request.get("startDate");
				Long endDate = (Long) request.get("endDate");
				Integer count = (Integer) request.get("count");
				String basetoken = (String) request.get("basetoken");
				String interval = (String) request.get("interval");
				Set<String> tokenids = new HashSet<>((List<String>) request.get("tokenids"));
				// logger.debug(request.toString() );

				if (count != null) {
					// logger.debug("count"+count);
					AbstractResponse response = orderTickerService.getLastMatchingEvents(tokenids, basetoken, store);
					this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
				} else {
					AbstractResponse response;
					if ("43200".equals(interval)) {
						response = orderTickerService.getTimeAVBGBetweenMatchingEvents(tokenids, basetoken, null, null,
								store);
					} else {
						response = orderTickerService.getTimeBetweenMatchingEvents(tokenids, basetoken,
								startDate / 1000, endDate / 1000, store);
					}

					this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
				}
			}
				break;
			case getChainNumber: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
					Json.jsonmapper().readValue(reqStr, Map.class);
					AbstractResponse response = blockService.getMaxConfirmedReward(store);
	
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;

			case getAllConfirmedReward: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
                Json.jsonmapper().readValue(reqStr, Map.class);
                AbstractResponse response = blockService.getAllConfirmedReward(store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case findRetryBlocks: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				AbstractResponse response = this.blockService.findRetryBlocks(request, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;

			case getSessionRandomNum: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String pubKey = (String) request.get("pubKey");
				AbstractResponse response = this.accessPermissionedService.getSessionRandomNumResp(pubKey, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;

			case addAccessGrant: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String pubKey = (String) request.get("pubKey");
				this.accessGrantService.addAccessGrant(pubKey, store);
				this.outPrintJSONString(httpServletResponse, AbstractResponse.createEmptyResponse(), watch, reqCmd);
			}
				break;

			case deleteAccessGrant: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String pubKey = (String) request.get("pubKey");
				this.accessGrantService.deleteAccessGrant(pubKey, store);
				this.outPrintJSONString(httpServletResponse, AbstractResponse.createEmptyResponse(), watch, reqCmd);
			}
				break;

 
			default:
				break;
			}
		} catch (BlockStoreException e) {
			logger.error("reqCmd : {} from {}, size : {}, started.", reqCmd, httprequest.getRemoteAddr(),
					bodyByte.length, e);
			AbstractResponse resp = ErrorResponse.create(101);
			resp.setErrorcode(101);
			resp.setMessage(e.getLocalizedMessage());
			this.outPrintJSONString(httpServletResponse, resp, watch, reqCmd);
		} catch (NoBlockException e) {
			logger.info("reqCmd : {} from {}, size : {}, started.", reqCmd, httprequest.getRemoteAddr(),
					bodyByte.length);
			logger.error("", e);
			AbstractResponse resp = ErrorResponse.create(404);
			resp.setErrorcode(404);
			resp.setMessage(e.getLocalizedMessage());
			this.outPrintJSONString(httpServletResponse, resp, watch, reqCmd);
		} catch (Throwable exception) {
			logger.error("reqCmd : {}, reqHex : {}, {},error.", reqCmd, bodyByte.length, remoteAddr(httprequest),
					exception);
			AbstractResponse resp = ErrorResponse.create(100);
			StringWriter sw = new StringWriter();
			exception.printStackTrace(new PrintWriter(sw));
			resp.setMessage(sw.toString());
			this.outPrintJSONString(httpServletResponse, resp, watch, reqCmd);
		} finally {
			store.close();
			if (watch.elapsed(TimeUnit.MILLISECONDS) > 1000)
                logger.info("{} takes {} from {}", reqCmd, watch.elapsed(TimeUnit.MILLISECONDS), remoteAddr(httprequest));
			watch.stop();
		}
	}

	@RequestMapping("/")
	public String index() {
		return "Bigtangle";
	}

	private void batchBlock(byte[] bodyByte, HttpServletResponse httpServletResponse, Stopwatch watch,
			BlockStoreInterface store) throws Exception {
		Block block = networkParameters.getDefaultSerializer().makeBlock(bodyByte);

		if (serverConfiguration.getMyserverblockOnly()) {
			if (!blockService.existMyserverblocks(block.getPrevBlockHash(), store)) {
				AbstractResponse resp = ErrorResponse.create(101);
				resp.setErrorcode(403);
				resp.setMessage("server accept only his tip selection for validation");
				this.outPrintJSONString(httpServletResponse, resp, watch, "batchBlock");
			} else {
				blockService.batchBlock(block, store);
				// deleteRegisterBlock(block, store);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, "batchBlock");
			}
		} else {
			blockService.batchBlock(block, store);
			// deleteRegisterBlock(block, store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, "batchBlock");
		}
	}

	private void errorLimit(HttpServletResponse httpServletResponse, Stopwatch watch) throws Exception {
		AbstractResponse resp = ErrorResponse.create(101);
		resp.setErrorcode(403);
		resp.setMessage(" limit reached. ");
		this.outPrintJSONString(httpServletResponse, resp, watch, "errorLimit");
	}

	private boolean checkPermission(HttpServletResponse httpServletResponse, HttpServletRequest httprequest,
			Stopwatch watch, BlockStoreInterface store) throws Exception {
		if (!serverConfiguration.getPermissioned()) {
			return true;
		}

		if (httprequest.getRequestURI().endsWith("getSessionRandomNum")) {
			return true;
		}

		// check Permissionadmin
		String header = httprequest.getHeader("accessToken");
		String pubkey = header.split(",")[0];
		byte[] pub = Utils.HEX.decode(pubkey);
		PQKey ecKey = PQKey.fromPublicOnly(pub);

		final String address = ecKey.toAddress(networkParameters).toHex();
		if (!Utils.isBlank(serverConfiguration.getPermissionadmin())
				&& serverConfiguration.getPermissionadmin().equals(address)) {
			return true;
		}

		int count = this.accessGrantService.getCountAccessGrantByAddress(address, store);
		if (count == 0) {
			AbstractResponse resp = ErrorResponse.create(100);
			resp.setMessage("no auth");
			this.outPrintJSONString(httpServletResponse, resp, watch, "checkPermission");
			return false;
		}

		if (!checkAuth(httprequest, store)) {
			AbstractResponse resp = ErrorResponse.create(100);
			resp.setMessage("no auth");
			this.outPrintJSONString(httpServletResponse, resp, watch, "checkPermission");
			return false;
		}

		return true;
	}

	private boolean checkReady(HttpServletResponse httpServletResponse, Stopwatch watch)
			throws Exception {
		if (!serverConfiguration.checkService()) {
			AbstractResponse resp = ErrorResponse.create(103);
			resp.setMessage("service is not ready.");
			this.outPrintJSONString(httpServletResponse, resp, watch, "checkReady");
			return false;
		} else {
			return true;
		}
	}

	public boolean checkAuth(HttpServletRequest httprequest,
							 BlockStoreInterface store) {
		String header = httprequest.getHeader("accessToken");
		boolean flag = false;
		if (header != null && !header.trim().isEmpty()) {
			HttpSession session = httprequest.getSession(true);
			if ("key_verified".equals(session.getAttribute("key_verify_flag"))) {
                return true;
			}
			String pubkey = header.split(",")[0];
			String signHex = header.split(",")[1];
			String accessToken = header.split(",")[2];
			PQKey key = PQKey.fromPublicOnly(Utils.HEX.decode(pubkey));

			byte[] buf = Utils.HEX.decode(accessToken);
			byte[] signature = Utils.HEX.decode(signHex);
			flag = net.bigtangle.crypto.pq.PQScriptUtils.verifyPQ(key.getPubKey(), signature, Sha256Hash.of(buf));

			if (flag) {
				int count = this.accessPermissionedService.checkSessionRandomNumResp(pubkey, accessToken, store);
				flag = count > 0;
			}
			if (flag) {
				HttpSession a = httprequest.getSession(true);
				if (a != null) {
					a.setAttribute("key_verify_flag", "key_verified");
				}
			}
		}
		return flag;
	}


	public void gzipBinary(HttpServletResponse httpServletResponse, AbstractResponse response, String reqCmd)
			throws Exception {
		byte[] data = Json.jsonmapper().writeValueAsBytes(response);
		httpServletResponse.setContentLength(data.length);
		httpServletResponse.getOutputStream().write(data);
		httpServletResponse.getOutputStream().flush();
	}

	public void outPointBinaryArray(HttpServletResponse httpServletResponse, byte[] data, String reqCmd)
			throws Exception {
		httpServletResponse.setCharacterEncoding("UTF-8");

		HashMap<String, Object> result = new HashMap<>();
		result.put("dataHex", Utils.HEX.encode(data));
		httpServletResponse.getOutputStream().write(Json.jsonmapper().writeValueAsBytes(result));
		httpServletResponse.getOutputStream().flush();
	}

	public void outPrintJSONString(HttpServletResponse httpServletResponse, AbstractResponse response, Stopwatch watch,
			String reqCmd) throws Exception {
		long duration = watch.elapsed(TimeUnit.MILLISECONDS);
		response.setDuration(duration);
		gzipBinary(httpServletResponse, response, reqCmd);
	}

	// server may accept only block from his server
	public void register(Block block, BlockStoreInterface store) throws BlockStoreException {
		if (serverConfiguration.getMyserverblockOnly())
			blockService.insertMyserverblocks(block.getPrevBlockHash(), block.getHash(), System.currentTimeMillis(),
					store);
	}

	public void deleteRegisterBlock(Block block, BlockStoreInterface store) throws BlockStoreException {
		if (serverConfiguration.getMyserverblockOnly()) {
			blockService.deleteMyserverblocks(block.getPrevBlockHash(), store);
		}
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
