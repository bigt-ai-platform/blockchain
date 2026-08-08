package net.bigtangle.server;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.base.Stopwatch;

import jakarta.servlet.http.HttpServletResponse;
import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.layer1.service.OrderTickerService;
import net.bigtangle.layer1.service.OrderdataService;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.ErrorResponse;
import net.bigtangle.response.OkResponse;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.ValidatorDutyService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

/**
 * Layer-1 ordermatch node REST API. Extends {@link BaseDispatcherController}
 * with the commands only the order-matching layer provides.
 *
 * <p>Order-matching-only commands:
 * <ul>
 *   <li>{@code getOrders}, {@code getOrdersTicker} — order book / matching
 *       events (order-matching tables)</li>
 *   <li>{@code stakeDeposit}, {@code activateValidator} — the L1 order chain
 *       runs its own PoS validator set (a separate validator key from Layer 0)</li>
 * </ul>
 */
@RestController
@RequestMapping("/")
public class DispatcherController extends BaseDispatcherController {

	@Autowired
	private OrderdataService orderdataService;
	@Autowired
	private OrderTickerService orderTickerService;
	@Autowired
	private StakeService stakeService;
	@Autowired
	private ValidatorDutyService validatorDutyService;

	@Override
	protected String getChainName() {
		return "Bigtangle";
	}

	@Override
	protected boolean handleLayerSpecific(ReqCmd reqCmd, byte[] bodyByte, HttpServletResponse httpServletResponse,
			Stopwatch watch, BlockStoreInterface store, String reqCmdName) throws Exception {
		switch (reqCmd) {
		case getOrders: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			String address = (String) request.get("address");
			String tokenid = (String) request.get("tokenid");
			List<String> addresses = (List<String>) request.get("addresses");
			AbstractResponse response = orderdataService.getOrderdataList(address, addresses, tokenid,
					store);
			this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			return true;
		}
		case getOrdersTicker: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			Long startDate = (Long) request.get("startDate");
			Long endDate = (Long) request.get("endDate");
			Integer count = (Integer) request.get("count");
			String basetoken = (String) request.get("basetoken");
			String interval = (String) request.get("interval");
			Set<String> tokenids = new HashSet<>((List<String>) request.get("tokenids"));

			if (count != null) {
				AbstractResponse response = orderTickerService.getLastMatchingEvents(tokenids, basetoken, store);
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			} else {
				AbstractResponse response;
				if ("43200".equals(interval)) {
					response = orderTickerService.getTimeAVBGBetweenMatchingEvents(tokenids, basetoken, null, null,
							store);
				} else {
					response = orderTickerService.getTimeBetweenMatchingEvents(tokenids, basetoken,
							startDate / 1000, endDate / 1000, store);
				}
				this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			}
			return true;
		}
		case stakeDeposit: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			// Security: never accept a raw private key over HTTP. The STAKE
			// transaction is signed with the server's CONFIGURED validator
			// key (pos.validatorKey), and the request pubkey must match it.
			if (request.containsKey("privateKey")) {
				this.outPrintJSONString(httpServletResponse,
						ErrorResponse.create(403), watch, reqCmdName);
				return true;
			}
			String pubkeyHex = (String) request.get("pubkey");
			PQKey configuredKey = validatorDutyService.getValidatorKey();
			if (configuredKey == null) {
				this.outPrintJSONString(httpServletResponse,
						ErrorResponse.create(403), watch, reqCmdName);
				return true;
			}
			if (!Utils.HEX.encode(configuredKey.getPublicKeyBytes()).equals(pubkeyHex)) {
				this.outPrintJSONString(httpServletResponse,
						ErrorResponse.create(403), watch, reqCmdName);
				return true;
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
				this.outPrintJSONString(httpServletResponse, ErrorResponse.create(404), watch, reqCmdName);
				return true;
			}
			if (request.containsKey("withdrawalCredentials")) {
				stakeService.processDeposit(selected,
						Utils.HEX.decode((String) request.get("withdrawalCredentials")), depositKey, store);
			} else {
				stakeService.processDeposit(selected, depositKey.getPubKey(), depositKey, store);
			}
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
		case activateValidator: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			String pubkeyHex = (String) request.get("pubkey");
			long epoch = Long.parseLong(request.getOrDefault("epoch", "0").toString());
			stakeService.activateValidator(Utils.HEX.decode(pubkeyHex), epoch, store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
		default:
			return false;
		}
	}
}
