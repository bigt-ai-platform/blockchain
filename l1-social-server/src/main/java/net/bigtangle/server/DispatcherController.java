package net.bigtangle.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.base.Stopwatch;

import jakarta.servlet.http.HttpServletResponse;
import net.bigtangle.core.Address;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.PQKey;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.ErrorResponse;
import net.bigtangle.response.OkResponse;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.ValidatorDutyService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.server.service.CasperService;
import net.bigtangle.utils.Json;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Layer-1 payment node REST API. Shares the common handlers (getTip, saveBlock,
 * getOutputs, ...) with the other L1 layers via {@link BaseDispatcherController}.
 *
 * Adds the minimal PoS bootstrap handlers (stakeDeposit / activateValidator /
 * getValidators) so a local L1-SOCIAL chain can form its own validator set —
 * same semantics as layer0-server.
 */
@RestController
@RequestMapping("/")
public class DispatcherController extends BaseDispatcherController {

	@Autowired
	private StakeService stakeService;

	@Autowired
	private ValidatorDutyService validatorDutyService;

	@Autowired
	private NetworkParameters networkParameters;

	@Autowired
	private CasperService casperService;

	@Override
	protected String getChainName() {
		return "Bigtangle Social L1";
	}

	/**
	 * Social-record ingestion: submitted transactions carrying a social.v1
	 * MemoInfo payload are validated (schema v2) and converted to typed
	 * UserData transactions (dataclassname="social.v1", data=record JSON).
	 * This is the consensus-side gate; group membership state checks belong
	 * in the confirmation handler as the social store matures.
	 */
	private void promoteSocialRecord(Transaction tx, BlockStoreInterface store) throws Exception {
		String memo = tx.getMemo();
		if (memo == null || !memo.contains("social.v1")) return;
		Map<String, Object> memoMap = Json.jsonmapper().readValue(memo, Map.class);
		Object kvObj = memoMap.get("kv");
		if (!(kvObj instanceof List)) throw new IllegalArgumentException("malformed social memo");
		for (Object o : (List<?>) kvObj) {
			@SuppressWarnings("unchecked")
			Map<String, Object> kv = (Map<String, Object>) o;
			if (!"social.v1".equals(kv.get("key"))) continue;
			String recordJson = String.valueOf(kv.get("value"));
			Map<String, Object> rec = Json.jsonmapper().readValue(recordJson, Map.class);
			if (!String.valueOf(rec.get("type")).startsWith("social."))
				throw new IllegalArgumentException("unknown social type");
			if (rec.get("from") == null || rec.get("to") == null)
				throw new IllegalArgumentException("from/to required");
			tx.setMemo(null);
			tx.setDataClassName(net.bigtangle.core.DataClassName.SocialRecord.name());
			tx.setData(recordJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return;
		}
	}

	@Override
	protected boolean handleLayerSpecific(ReqCmd reqCmd, byte[] bodyByte, HttpServletResponse httpServletResponse,
			Stopwatch watch, BlockStoreInterface store, String reqCmdName) throws Exception {
		if (reqCmd == ReqCmd.submitTransaction) {
			Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(bodyByte);
			promoteSocialRecord(tx, store);
			// re-serialize the (possibly promoted) tx for base handling
			bodyByte = tx.bitcoinSerialize();
		}
		switch (reqCmd) {
		case stakeDeposit: {
			String reqStr = new String(bodyByte, java.nio.charset.StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			if (request.containsKey("privateKey")) {
				this.outPrintJSONString(httpServletResponse, ErrorResponse.create(403), watch, reqCmdName);
				return true;
			}
			String pubkeyHex = (String) request.get("pubkey");
			PQKey configuredKey = validatorDutyService.getValidatorKey();
			if (configuredKey == null) {
				this.outPrintJSONString(httpServletResponse, ErrorResponse.create(403), watch, reqCmdName);
				return true;
			}
			if (!Utils.HEX.encode(configuredKey.getPublicKeyBytes()).equals(pubkeyHex)) {
				this.outPrintJSONString(httpServletResponse, ErrorResponse.create(403), watch, reqCmdName);
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
			stakeService.processDeposit(selected, depositKey.getPubKey(), depositKey, store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
		case activateValidator: {
			String reqStr = new String(bodyByte, java.nio.charset.StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			String pubkeyHex = (String) request.get("pubkey");
			long epoch = Long.parseLong(request.getOrDefault("epoch", "0").toString());
			stakeService.activateValidator(Utils.HEX.decode(pubkeyHex), epoch, store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
		case submitAttestation: {
			net.bigtangle.core.AttestationData att = Json.jsonmapper().readValue(
					new String(bodyByte, java.nio.charset.StandardCharsets.UTF_8),
					net.bigtangle.core.AttestationData.class);
			casperService.processVote(att, store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
		case getAttestations: {
			String reqStr = new String(bodyByte, java.nio.charset.StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			long slot = Long.parseLong(request.getOrDefault("slot", "0").toString());
			java.util.List<net.bigtangle.core.AttestationData> attestations =
					store.getAttestationsForSlot(slot);
			Map<String, Object> result = new java.util.HashMap<>();
			result.put("attestations", attestations);
			this.outPrintJSONString(httpServletResponse,
					net.bigtangle.response.GetStringResponse.create(Json.jsonmapper().writeValueAsString(result)),
					watch, reqCmdName);
			return true;
		}
		case getValidators: {
			java.util.List<net.bigtangle.core.StakeRecord> validators = store.getActiveStakeDeposits();
			Map<String, Object> result = new java.util.HashMap<>();
			result.put("validators", validators);
			this.outPrintJSONString(httpServletResponse,
					net.bigtangle.response.GetStringResponse.create(
							com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
									.writeValueAsString(result)),
					watch, reqCmdName);
			return true;
		}
		default:
			return false;
		}
	}
}
