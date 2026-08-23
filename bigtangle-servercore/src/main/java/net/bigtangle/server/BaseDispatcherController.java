package net.bigtangle.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
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
import net.bigtangle.layer0.service.MultiSignService;
import net.bigtangle.layer0.service.MultiSignServiceCreate;
import net.bigtangle.layer0.service.OutputService;
import net.bigtangle.layer0.service.TokenDomainnameService;
import net.bigtangle.layer0.service.TokensService;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.server.data.TransactionStatus;
import net.bigtangle.server.data.TransactionStatusRecord;
import net.bigtangle.response.ErrorResponse;
import net.bigtangle.response.GetBlockListResponse;
import net.bigtangle.response.GetStringResponse;
import net.bigtangle.response.GetTransactionStatusResponse;
import net.bigtangle.response.OkResponse;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.AccessGrantService;
import net.bigtangle.server.service.AccessPermissionedService;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.BlockService;
import net.bigtangle.server.service.BlockServiceCreate;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.server.service.MempoolService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.SubtanglePermissionService;
import net.bigtangle.server.service.UserDataService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

/**
 * Shared REST dispatcher for the layer-minimal L1 servers (l1-contract,
 * l1-pai, ...). Holds the request plumbing (auth, errors, response helpers)
 * and the common command handlers. Subclasses register as {@code @RestController}
 * with {@code @RequestMapping("/")} and only supply layer-specific handlers via
 * {@link #handleLayerSpecific(ReqCmd, byte[], HttpServletResponse, Stopwatch, BlockStoreInterface, String)}.
 *
 * <p>The layer-agnostic service dependencies are the {@code layer0.service}
 * types (defined in bigtangle-servercore), so every L1 server can reuse them
 * without duplicating empty {@code layer1.service} subclasses.
 */
public abstract class BaseDispatcherController implements DisposableBean {

	private static final Logger logger = LoggerFactory.getLogger(BaseDispatcherController.class);

	private ExecutorService requestExecutor = Executors.newFixedThreadPool(
			Math.max(32, Runtime.getRuntime().availableProcessors() * 8));

	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	protected UserDataService userDataService;
	@Autowired
	protected OutputService walletService;
	@Autowired
	protected BlockService blockService;
	@Autowired
	protected BlockServiceCreate blockServiceCreate;
	@Autowired
	protected BlockSaveService blockSaveService;
	@Autowired
	protected SubtanglePermissionService subtanglePermissionService;
	@Autowired
	protected ServerConfiguration serverConfiguration;
	@Autowired
	protected TokenDomainnameService tokenDomainnameService;
	@Autowired
	protected MultiSignService multiSignService;
	@Autowired
	protected MultiSignServiceCreate multiSignServiceCreate;
	@Autowired
	protected TokensService tokensService;
	@Autowired
	protected StoreService storeService;
	@Autowired
	protected AccessPermissionedService accessPermissionedService;
	@Autowired
	protected AccessGrantService accessGrantService;
	@Autowired
	protected CacheBlockPrototypeService cacheBlockPrototypeService;
	@Autowired
	protected MempoolService mempoolService;

	@Override
	public void destroy() {
		requestExecutor.shutdownNow();
	}

	/** Layer-specific display name shown at the root path. */
	protected abstract String getChainName();

	@RequestMapping("/")
	public String index() {
		return getChainName();
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
			processDo(reqCmd, contentBytes, httpServletResponse, httprequest);
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
			writeJsonResponse(httpServletResponse, resp, reqCmd);
		} catch (java.util.concurrent.ExecutionException e) {
			logger.error("ERROR ExecutionException reqCmd={} cause={}", reqCmd,
					e.getCause() != null ? e.getCause().getClass().getName() + ": " + e.getCause().getMessage() : "null");
			logger.error("process ExecutionException for reqCmd={}", reqCmd, e.getCause());
			Stopwatch watch = Stopwatch.createStarted();
			AbstractResponse resp = ErrorResponse.create(101);
			resp.setMessage(e.getCause() != null ? e.getCause().getLocalizedMessage() : e.getLocalizedMessage());
			this.outPrintJSONString(httpServletResponse, resp, watch, reqCmd);
		}
	}

	/**
	 * Handles a command that is specific to this layer. Subclasses return
	 * {@code true} when they wrote a response; the shared dispatcher rejects
	 * unhandled commands otherwise.
	 */
	protected abstract boolean handleLayerSpecific(ReqCmd reqCmd, byte[] bodyByte,
			HttpServletResponse httpServletResponse, Stopwatch watch, BlockStoreInterface store, String reqCmdName)
			throws Exception;

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
			if (handleLayerSpecific(reqCmd0000, bodyByte, httpServletResponse, watch, store, reqCmd)) {
				return;
			}
			switch (reqCmd0000) {
			case getTip: {
				// Legacy full-block tip — used by bigtangle-ts Wallet.getTip()
				// and the benchmark suite. Returns the rolling block prototype
				// as {"dataHex": ...} for OkHttp3Util.postAndGetBlock.
				Block rollingBlock = cacheBlockPrototypeService.getBlockPrototype(store);
				this.outPointBinaryArray(httpServletResponse, rollingBlock.bitcoinSerialize(), reqCmd);
			}
				break;
			case getTips: {
				// Lightweight tip positions for client-side block assembly.
				// Replaces the old getTip full-block prototype: clients build
				// their block locally from these hashes; token creation is
				// re-parented server-side at signToken regardless.
				if (!userDataService.ipCheck(reqCmd, contentBytes, httprequest)) {
					logger.debug("bomb getTips {} {}", remoteAddr(httprequest), reqCmd);
					errorLimit(httpServletResponse, watch);
					return;
				}
				Block rollingBlock = cacheBlockPrototypeService.getBlockPrototype(store);
				HashMap<String, Object> tips = new HashMap<>();
				tips.put("prevBlockHash", rollingBlock.getPrevBlockHash().toString());
				tips.put("prevBranchBlockHash", rollingBlock.getPrevBranchBlockHash().toString());
				tips.put("height", rollingBlock.getHeight());
				tips.put("timeSeconds", rollingBlock.getTimeSeconds());
				tips.put("lastMiningRewardBlock", rollingBlock.getLastMiningRewardBlock());
				this.outPrintJSONString(httpServletResponse,
						GetStringResponse.create(Json.jsonmapper().writeValueAsString(tips)), watch, reqCmd);
			}
				break;
			case batchBlock: {
				batchBlock(bodyByte, httpServletResponse, watch, store);
			}
				break;
			case submitTransaction: {
				Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(bodyByte);
				mempoolService.submitTransaction(tx);
				recordMempoolStatus(tx, store);
				blockSaveService.broadcastTransaction(tx);
				this.outPrintJSONString(httpServletResponse, new OkResponse(), watch, reqCmd);
			}
				break;
			case submitTransactions: {
				java.io.DataInputStream dis = new java.io.DataInputStream(
						new java.io.ByteArrayInputStream(bodyByte));
				int count = 0;
				List<Transaction> batch = new ArrayList<>();
				while (dis.available() > 0) {
					int len = dis.readInt();
					byte[] txBytes = new byte[len];
					dis.readFully(txBytes);
					batch.add(networkParameters.getDefaultSerializer().makeTransaction(txBytes));
					count++;
				}
				// ONE shared store for the whole batch: UTXO verification reads
				// go through a single pooled connection instead of an
				// open/close cycle per transaction.
				mempoolService.submitTransactions(batch, store);
				// MEMPOOL status is best-effort bookkeeping; collecting the
				// records and flushing them with ONE batched upsert keeps a
				// DB round-trip off the per-tx ingest critical path (a 250-tx
				// batch previously issued 250 synchronous upserts).
				java.util.List<TransactionStatusRecord> mempoolStatuses = new ArrayList<>();
				for (Transaction tx : batch) {
					mempoolStatuses.add(new TransactionStatusRecord(
							tx.getHash(), TransactionStatus.MEMPOOL, null, null,
							TransactionStatusRecord.deriveAddress(tx, networkParameters),
							System.currentTimeMillis(), System.currentTimeMillis()));
					blockSaveService.broadcastTransaction(tx);
				}
				try {
					if (!mempoolStatuses.isEmpty()) {
						store.upsertTransactionStatuses(mempoolStatuses);
					}
				} catch (Exception e) {
					// status tracking is best-effort
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
			case getOutputsHistory: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String fromaddress = (String) request.get("fromaddress");
				String toaddress = (String) request.get("toaddress");
				Long starttime = request.get("starttime") == null ? null
						: ((Number) request.get("starttime")).longValue();
				Long endtime = request.get("endtime") == null ? null : ((Number) request.get("endtime")).longValue();
				AbstractResponse response = walletService.getOutputsHistory(fromaddress, toaddress, starttime, endtime,
						store);
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
			case getTransactionStatus: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String txHashHex = (String) request.get("txHash");
				if (txHashHex == null || txHashHex.isEmpty()) {
					this.outPrintJSONString(httpServletResponse,
							GetStringResponse.create("txHash is required"), watch, reqCmd);
					break;
				}
				net.bigtangle.server.data.TransactionStatusRecord record = store
						.getTransactionStatus(Sha256Hash.wrap(txHashHex));
				AbstractResponse response;
				if (record == null) {
					response = GetTransactionStatusResponse.createEmpty(txHashHex);
				} else {
					response = transactionStatusResponse(record, store);
				}
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;

			case getTransactionsStatusByAddress: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String address = (String) request.get("address");
				if (address == null || address.isEmpty()) {
					this.outPrintJSONString(httpServletResponse,
							GetStringResponse.create("address is required"), watch, reqCmd);
					break;
				}
				List<net.bigtangle.server.data.TransactionStatusRecord> records = store
						.getTransactionStatusesByAddress(address);
				List<GetTransactionStatusResponse> items = new ArrayList<>();
				for (net.bigtangle.server.data.TransactionStatusRecord r : records) {
					items.add(transactionStatusResponse(r, store));
				}
				this.outPrintJSONString(httpServletResponse,
						GetTransactionStatusResponse.GetTransactionsStatusResponse.create(items), watch, reqCmd);
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

			case getBlocksByHashList: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				@SuppressWarnings("unchecked")
				List<String> hashHexs = (List<String>) request.get("hashHexs");
				GetBlockListResponse response = this.blockService.getBlocksByHashList(hashHexs, store);
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
				long maxHeight = Long.parseLong(
						request.get("maxHeight") == null ? String.valueOf(Long.MAX_VALUE)
								: (String) request.get("maxHeight"));
				int limit = Integer.parseInt(
						request.get("limit") == null ? "500" : (String) request.get("limit"));
				GetBlockListResponse response = this.blockService
						.blocksFromNonChainHeigth(cutoffHeight, maxHeight, limit, store);
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
			case searchExchangeTokens: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String keyword = request.get("name") != null ? request.get("name").toString()
						: request.get("keyword") != null ? request.get("keyword").toString() : null;
				AbstractResponse response = tokensService.searchTokensByNameOrId(keyword, store);
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
			case getPendingTransactions: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Json.jsonmapper().readValue(reqStr, Map.class);
				// Serve the local mempool so peers can converge on the same set
				// of pending transactions even without a gossip mesh. Coinbase
				// txs are never shared (they are reward-chain internal).
				List<byte[]> serialized = new ArrayList<>();
				for (Transaction tx : mempoolService.getPending()) {
					if (tx.isCoinBase()) {
						continue;
					}
					serialized.add(tx.bitcoinSerialize());
				}
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.GetTransactionListResponse.create(serialized), watch, reqCmd);
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
				logger.warn("reqCmd {} rejected: not handled by this {} node", reqCmd,
						store.getStoreDomain());
				this.outPrintJSONString(httpServletResponse,
						ErrorResponse.create(100), watch, reqCmd);
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

	protected void batchBlock(byte[] bodyByte, HttpServletResponse httpServletResponse, Stopwatch watch,
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

	protected void errorLimit(HttpServletResponse httpServletResponse, Stopwatch watch) throws Exception {
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

	public void writeJsonResponse(HttpServletResponse httpServletResponse, AbstractResponse response, String reqCmd)
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
		writeJsonResponse(httpServletResponse, response, reqCmd);
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

	/** Best-effort: record MEMPOOL status for a user-submitted transaction. */
	private void recordMempoolStatus(Transaction tx, BlockStoreInterface store) {
		try {
			TransactionStatusRecord.mark(store, tx, TransactionStatus.MEMPOOL, null, null, networkParameters);
		} catch (Exception e) {
			// status tracking is best-effort
		}
	}

	/**
	 * Serves a transaction status. CONFIRMED is derived on read from the
	 * block's confirmed flag (the confirm path no longer writes it, see
	 * a8fda429b) so clients polling for CONFIRMED still observe finality.
	 */
	private GetTransactionStatusResponse transactionStatusResponse(
			net.bigtangle.server.data.TransactionStatusRecord record, BlockStoreInterface store) {
		net.bigtangle.server.data.TransactionStatus status = record.getStatus();
		Sha256Hash blockHash = record.getBlockHash();
		if (blockHash != null && status != net.bigtangle.server.data.TransactionStatus.CONFIRMED
				&& status != net.bigtangle.server.data.TransactionStatus.DROPPED) {
			try {
				if (store.isBlockConfirmed(blockHash)) {
					status = net.bigtangle.server.data.TransactionStatus.CONFIRMED;
				}
			} catch (Exception e) {
				logger.debug("confirm-status derivation failed for {}: {}", record.getTxHash(), e.getMessage());
			}
		}
		return GetTransactionStatusResponse.create(record.getTxHash().toString(), status.name(),
				blockHash == null ? null : blockHash.toString(), record.getChainlength(), record.getAddress(),
				record.getCreatedTime(), record.getUpdatedTime());
	}
}
