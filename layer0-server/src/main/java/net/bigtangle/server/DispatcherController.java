/*******************************************************************************
 * <p>
 *  Copyright   2018  Inasset GmbH. 
 *******************************************************************************/
package net.bigtangle.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;import java.util.ArrayList;
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
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.data.TransactionStatus;
import net.bigtangle.server.data.TransactionStatusRecord;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.ErrorResponse;
import net.bigtangle.response.GetBlockListResponse;
import net.bigtangle.response.GetStringResponse;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.response.GetTransactionStatusResponse;
import net.bigtangle.response.OkResponse;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.response.PermissionedAddressesResponse;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.core.Address;
import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.server.service.AccessGrantService;
import net.bigtangle.server.service.AccessPermissionedService;
import net.bigtangle.bridge.BridgeService;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.BlockService;
import net.bigtangle.server.service.CasperService;
import net.bigtangle.server.service.FeeService;
import net.bigtangle.server.service.MempoolService;
import net.bigtangle.server.service.SlashingService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.ValidatorDutyService;
import net.bigtangle.server.service.BlockServiceCreate;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.layer0.service.MultiSignService;
import net.bigtangle.layer0.service.MultiSignServiceCreate;
import net.bigtangle.layer0.service.OutputService;
import net.bigtangle.layer0.service.PayMultiSignService;
 
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.SubtanglePermissionService;
import net.bigtangle.layer0.service.TokenDomainnameService;
import net.bigtangle.layer0.service.TokensService;
import net.bigtangle.server.service.UserDataService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

@RestController
@RequestMapping("/")
public class DispatcherController implements DisposableBean {

	private static final Logger logger = LoggerFactory.getLogger(DispatcherController.class);

	/** Unique index counter for fundAddresses coinbases (see fundAddresses). */
	private static final java.util.concurrent.atomic.AtomicLong FUND_UTXO_INDEX = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);

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
	private TokensService tokensService;
	@Autowired
	private MultiSignService multiSignService;
	@Autowired
	private MultiSignServiceCreate multiSignServiceCreate;
	@Autowired
	private PayMultiSignService payMultiSignService;
	@Autowired
	private SubtanglePermissionService subtanglePermissionService;
	@Autowired
	ServerConfiguration serverConfiguration;
	@Autowired
	protected StoreService storeService;
	@Autowired
	private TokenDomainnameService tokenDomainnameService;

	 
	@Autowired
	private AccessPermissionedService accessPermissionedService;
	@Autowired
	private AccessGrantService accessGrantService;
	@Autowired
	protected CacheBlockPrototypeService cacheBlockPrototypeService;
	@Autowired
	private MempoolService mempoolService;
	@Autowired(required = false)
	private BridgeService bridgeService;
	@Autowired
	private CasperService casperService;
	@Autowired
	private SlashingService slashingService;
	@Autowired
	private StakeService stakeService;
	@Autowired
	private FeeService feeService;
	@Autowired
	private ValidatorDutyService validatorDutyService;

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
			case submitTransaction: {
				Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(bodyByte);
				mempoolService.submitTransaction(tx);
				recordMempoolStatus(tx, store);
				blockSaveService.broadcastTransaction(tx);
				this.outPrintJSONString(httpServletResponse, new net.bigtangle.response.OkResponse(), watch, reqCmd);
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
					recordMempoolStatus(tx, store);
					blockSaveService.broadcastTransaction(tx);
					count++;
				}
				net.bigtangle.response.GetStringResponse resp = new net.bigtangle.response.GetStringResponse();
				resp.setMessage(String.valueOf(count));
				this.outPrintJSONString(httpServletResponse, resp, watch, reqCmd);
			}
				break;
			case processPegIn: {
				if (bridgeService == null) {
					this.outPrintJSONString(httpServletResponse,
							net.bigtangle.response.ErrorResponse.create(503), watch, reqCmd);
					break;
				}
				// Accept a SIGNED transaction that spends the source UTXO and
				// pays the vault. BridgeService verifies the input scriptSig
				// (ownership proof) before anything is locked — a raw outpoint +
				// beneficiary is never enough to lock someone else's UTXO.
				Transaction pegInTx = networkParameters.getDefaultSerializer().makeTransaction(bodyByte);
				bridgeService.processPegIn(pegInTx, store);
				this.outPrintJSONString(httpServletResponse,
						new net.bigtangle.response.OkResponse(), watch, reqCmd);
			}
				break;
			case processPegOut: {
				if (bridgeService == null) {
					this.outPrintJSONString(httpServletResponse,
							net.bigtangle.response.ErrorResponse.create(503), watch, reqCmd);
					break;
				}
				// Process peg-out for the latest confirmed anchor of this chain.
				// Anchors are indexed by their own CROSSTANGLE block hash, NOT
				// by a vault's peg-in block hash, so look them up by chain.
				AnchorRecord latest = store.getLatestAnchorByChainId(networkParameters.getChainId());
				if (latest != null) {
					bridgeService.processPegOut(latest, store);
				}
				this.outPrintJSONString(httpServletResponse,
						new net.bigtangle.response.OkResponse(), watch, reqCmd);
			}
				break;
			case batchBlock: {
				batchBlock(bodyByte, httpServletResponse, watch, store);
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
				outputHistory(bodyByte, httpServletResponse, watch, store);
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

			case searchTokens: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				GetTokensResponse response = tokensService.searchTokens((String) request.get("name"), store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case searchWebTokens: {
                AbstractResponse response = tokensService.getWebTokensList(store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case searchExchangeTokens: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				GetTokensResponse response = tokensService.searchExchangeTokens((String) request.get("name"), store);
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
					response = GetTransactionStatusResponse.create(record.getTxHash().toString(),
							record.getStatus().name(),
							record.getBlockHash() == null ? null : record.getBlockHash().toString(),
							record.getChainlength(), record.getAddress(), record.getCreatedTime(),
							record.getUpdatedTime());
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
					items.add(GetTransactionStatusResponse.create(r.getTxHash().toString(), r.getStatus().name(),
							r.getBlockHash() == null ? null : r.getBlockHash().toString(), r.getChainlength(),
							r.getAddress(), r.getCreatedTime(), r.getUpdatedTime()));
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
						 Integer.valueOf(tokenindex), (List<String>) request.get("addresses"),
                        isSign != null && isSign, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case signToken: {
				Block block = networkParameters.getDefaultSerializer().makeBlock(bodyByte);
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
			case launchPayMultiSign: {
				this.payMultiSignService.launchPayMultiSign(bodyByte, store);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;
			case payMultiSign: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				AbstractResponse response = this.payMultiSignService.payMultiSign(request, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getPayMultiSignList: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				List<String> keyStrHex000 = Json.jsonmapper().readValue(reqStr, List.class);
				AbstractResponse response = this.payMultiSignService.getPayMultiSignList(keyStrHex000, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getPayMultiSignAddressList: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String orderid = (String) request.get("orderid");
				AbstractResponse response = this.payMultiSignService.getPayMultiSignAddressList(orderid, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case payMultiSignDetails: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String orderid = (String) request.get("orderid");
				AbstractResponse response = this.payMultiSignService.getPayMultiSignDetails(orderid, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmd);
			}
				break;
			case getOutputByKey: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String hexStr = (String) request.get("hexStr");
				AbstractResponse response = walletService.getOutputsWithHexStr(hexStr, store);
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
			case getTokenPermissionedAddresses: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				final String domainNameBlockHash = (String) request.get("domainNameBlockHash");
				PermissionedAddressesResponse response = this.tokenDomainnameService
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
							this.tokenDomainnameService.queryParentDomainnameBlockHash(domainname, store), watch,
							reqCmd);
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

			case getAnchors: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String chainId = (String) request.get("chainId");
				long sinceHeight = Long.parseLong(request.get("sinceHeight") + "");
				int limit = Integer.parseInt(request.get("limit") + "");
				List<AnchorRecord> anchors = storeService.getStore().getAnchorsByChainId(
						chainId, sinceHeight, limit);
				String json = Json.jsonmapper().writeValueAsString(anchors);
				httpServletResponse.setCharacterEncoding("UTF-8");
				httpServletResponse.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
			}
				break;

			case submitAttestation: {
				AttestationData att = Json.jsonmapper().readValue(bodyByte, AttestationData.class);
				casperService.processVote(att, store);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;
			case getAttestations: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				long slot = Long.parseLong(request.getOrDefault("slot", "0").toString());
				List<AttestationData> attestations = store.getAttestationsForSlot(slot);
				Map<String, Object> result = new HashMap<>();
				result.put("attestations", attestations);
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.GetStringResponse.create(
								Json.jsonmapper().writeValueAsString(result)), watch, reqCmd);
			}
				break;
			case processWithdrawal: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				long epoch = Long.parseLong(request.getOrDefault("epoch", "0").toString());
				stakeService.processWithdrawals(epoch, store);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;
			case requestValidatorExit: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String pubkeyHex = (String) request.get("pubkey");
				String signatureHex = (String) request.get("signature");
				// The validator MUST prove key ownership with a signature; the
				// exit travels as a consensus BLOCKTYPE_EXIT block.
				if (signatureHex == null || signatureHex.isEmpty()) {
					this.outPrintJSONString(httpServletResponse,
							net.bigtangle.response.ErrorResponse.create(403), watch, reqCmd);
					break;
				}
				stakeService.submitExit(Utils.HEX.decode(pubkeyHex),
						Utils.HEX.decode(signatureHex), store);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;
			case submitSlashingProof: {
				AttestationData att1 = null, att2 = null;
				try {
					Map<String, Object> req = Json.jsonmapper().readValue(bodyByte, Map.class);
					if (req.containsKey("attestation1")) {
						att1 = Json.jsonmapper().convertValue(req.get("attestation1"), AttestationData.class);
					}
					if (req.containsKey("attestation2")) {
						att2 = Json.jsonmapper().convertValue(req.get("attestation2"), AttestationData.class);
					}
				} catch (Exception e) {
					this.outPrintJSONString(httpServletResponse,
							net.bigtangle.response.ErrorResponse.create(400), watch, reqCmd);
					break;
				}
				// A slashing proof must carry TWO authenticated attestations
				// from the SAME validator that form a slashable pattern
				// (double vote or surround vote). The slash is proposed as a
				// consensus BLOCKTYPE_SLASHING block, applied by every node.
				if (att1 == null || att2 == null
						|| att1.getValidatorPubkey() == null
						|| !java.util.Arrays.equals(att1.getValidatorPubkey(), att2.getValidatorPubkey())
						|| !casperService.verifyAttestation(att1)
						|| !casperService.verifyAttestation(att2)) {
					this.outPrintJSONString(httpServletResponse,
							net.bigtangle.response.ErrorResponse.create(403), watch, reqCmd);
					break;
				}
				boolean doubleVote = att1.getSlot() == att2.getSlot()
						&& !att1.getBeaconBlockHash().equals(att2.getBeaconBlockHash());
				boolean surround = (att1.getSourceEpoch() < att2.getSourceEpoch()
						&& att2.getTargetEpoch() < att1.getTargetEpoch())
						|| (att2.getSourceEpoch() < att1.getSourceEpoch()
								&& att1.getTargetEpoch() < att2.getTargetEpoch());
				if (!doubleVote && !surround) {
					this.outPrintJSONString(httpServletResponse,
							net.bigtangle.response.ErrorResponse.create(400), watch, reqCmd);
					break;
				}
				stakeService.submitSlashing(att1, att2, store);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;
			case stakeDeposit: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				// Security: never accept a raw private key over HTTP. The STAKE
				// transaction is signed with the server's CONFIGURED validator
				// key (pos.validatorKey), and the request pubkey must match it.
				if (request.containsKey("privateKey")) {
					this.outPrintJSONString(httpServletResponse,
							net.bigtangle.response.ErrorResponse.create(403), watch, reqCmd);
					break;
				}
				String pubkeyHex = (String) request.get("pubkey");
				PQKey configuredKey = validatorDutyService.getValidatorKey();
				if (configuredKey == null) {
					this.outPrintJSONString(httpServletResponse,
							net.bigtangle.response.ErrorResponse.create(403), watch, reqCmd);
					break;
				}
				if (!Utils.HEX.encode(configuredKey.getPublicKeyBytes()).equals(pubkeyHex)) {
					this.outPrintJSONString(httpServletResponse,
							net.bigtangle.response.ErrorResponse.create(403), watch, reqCmd);
					break;
				}
				PQKey depositKey = configuredKey;
				String amountStr = (String) request.get("amount");
				BigInteger amount = new BigInteger(amountStr);
				Address addr = Address.fromHash160(networkParameters, Utils.sha256hash160(depositKey.getPubKey()));
				List<UTXO> utxos = store.getOpenTransactionOutputs(addr.toBase58());
				UTXO selected = null;
				for (UTXO u : utxos) {
					if (u.getValue().getValue().compareTo(amount) >= 0
							&& java.util.Arrays.equals(u.getValue().getTokenid(), NetworkParameters.BIGTANGLE_TOKENID)) {
						selected = u;
						break;
					}
				}
				if (selected == null) {
					this.outPrintJSONString(httpServletResponse, ErrorResponse.create(404), watch, reqCmd);
					break;
				}
				if (request.containsKey("withdrawalCredentials")) {
					stakeService.processDeposit(selected,
							Utils.HEX.decode((String) request.get("withdrawalCredentials")), depositKey, store);
				} else {
					stakeService.processDeposit(selected, depositKey.getPubKey(), depositKey, store);
				}
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;
			case activateValidator: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String pubkeyHex = (String) request.get("pubkey");
				long epoch = Long.parseLong(request.getOrDefault("epoch", "0").toString());
				stakeService.activateValidator(Utils.HEX.decode(pubkeyHex), epoch, store);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;
			case getValidators: {
				List<StakeRecord> validators = store.getActiveStakeDeposits();
				Map<String, Object> result = new HashMap<>();
				result.put("validators", validators);
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.GetStringResponse.create(
								Json.jsonmapper().writeValueAsString(result)), watch, reqCmd);
			}
				break;
			case getBaseFee: {
				Map<String, Object> result = new HashMap<>();
				result.put("baseFee", feeService.getBaseFee());
				result.put("feeDefault", Coin.FEE_DEFAULT.getValue().longValue());
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.GetStringResponse.create(
								Json.jsonmapper().writeValueAsString(result)), watch, reqCmd);
			}
				break;
			case setValidatorKey: {
				String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
				Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
				String keyHex = (String) request.get("privateKey");
				if (keyHex != null && !keyHex.isEmpty()) {
					PQKey key = PQKey.createNew();
				}
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
			}
				break;
			case getValidatorKey: {
				Map<String, Object> result = new HashMap<>();
				result.put("configured", validatorDutyService.getValidatorKey() != null);
				PQKey key = validatorDutyService.getValidatorKey();
				if (key != null) {
					result.put("pubkey", Utils.HEX.encode(key.getPubKey()));
				}
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.GetStringResponse.create(
								Json.jsonmapper().writeValueAsString(result)), watch, reqCmd);
			}
				break;
			case fundAddresses: {
				@SuppressWarnings("unchecked")
				Map<String, Object> req = Json.jsonmapper().readValue(bodyByte, Map.class);
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> entries = (List<Map<String, Object>>) req.get("addresses");
				Block genesis = UtilGeneseBlock.createGenesis(this.networkParameters);
				Sha256Hash genesisHash = genesis.getHash();
				// All funded UTXOs share the genesis hash, so give each one a
				// globally-unique index to avoid colliding with other fundAddresses
				// calls (concurrent remote tests) or the genesis coinbase at index 0.
				long startIndex = FUND_UTXO_INDEX.addAndGet(entries.size()) - entries.size();
				List<UTXO> utxos = new ArrayList<>();
				for (int i = 0; i < entries.size(); i++) {
					Map<String, Object> entry = entries.get(i);
					String addrStr = (String) entry.get("address");
					BigInteger value = entry.containsKey("value")
							? BigInteger.valueOf(((Number) entry.get("value")).longValue())
							: NetworkParameters.BigtangleCoinTotal.divide(BigInteger.valueOf(entries.size()));
						String pubkeyHex = (String) entry.get("pubkey");
				UTXO utxo = new UTXO();
				utxo.setHash(genesisHash);
				utxo.setIndex(startIndex + i);
				utxo.setValue(new Coin(value, NetworkParameters.BIGTANGLE_TOKENID));
				utxo.setAddress(addrStr);
				if (pubkeyHex != null) {
					byte[] pubkeyBytes = Utils.HEX.decode(pubkeyHex);
					PQKey key = PQKey.fromPublicOnly(pubkeyBytes);
					utxo.setScript(ScriptBuilder.createOutputScript(key));
					byte[] pubKeyHash = Utils.sha256hash160(pubkeyBytes);
					utxo.setAddress(Address.fromHash160(networkParameters, pubKeyHash).toBase58());
				} else {
					utxo.setScript(ScriptBuilder
							.createOutputScript(Address.fromBase58(networkParameters, addrStr)));
				}
					utxo.setCoinbase(true);
					utxo.setBlockHash(genesisHash);
					utxo.setTokenid(NetworkParameters.BIGTANGLE_TOKENID_STRING);
					utxo.setConfirmed(true);
					utxo.setSpent(false);
					utxos.add(utxo);
				}
				store.addUnspentTransactionOutput(utxos);
				this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmd);
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

	private void outputHistory(byte[] bodyByte, HttpServletResponse httpServletResponse, Stopwatch watch,
			BlockStoreInterface store)
			throws Exception {
		String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
		@SuppressWarnings("unchecked")
		Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
		String fromaddress = request.get("fromaddress") == null ? "" : request.get("fromaddress").toString();
		String toaddress = request.get("toaddress") == null ? "" : request.get("toaddress").toString();
		Long starttime = request.get("starttime") == null ? null : Long.valueOf(request.get("starttime").toString());
		Long endtime = request.get("endtime") == null ? null : Long.valueOf(request.get("endtime").toString());
		AbstractResponse response = walletService.getOutputsHistory(fromaddress, toaddress, starttime, endtime, store);
		this.outPrintJSONString(httpServletResponse, response, watch, "outputHistory");
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
			flag = PQScriptUtils.verifyPQ(key.getPublicKeyBytes(), signature, Sha256Hash.wrap(buf));

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
}
