package net.bigtangle.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.base.Stopwatch;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import net.bigtangle.core.Address;
import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.layer0.service.PayMultiSignService;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.ErrorResponse;
import net.bigtangle.response.GetStringResponse;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.response.OkResponse;
import net.bigtangle.bridge.BridgeService;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.service.CasperService;
import net.bigtangle.server.service.FeeService;
import net.bigtangle.server.service.SlashingService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.ValidatorDutyService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

/**
 * Layer-0 settlement chain REST API. Extends {@link BaseDispatcherController}
 * with the commands that only Layer 0 provides.
 *
 * <p>Layer-0-only services (unavailable on the layer-1 nodes):
 * <ul>
 *   <li>token creation ({@code signToken} in the base is chain-gated: only the
 *       Layer-0 params allow {@code BLOCKTYPE_TOKEN_CREATION})</li>
 *   <li>token search ({@code searchTokens}, {@code searchWebTokens},
 *       {@code searchExchangeTokens})</li>
 *   <li>multi-sign payment ({@code launchPayMultiSign}, {@code payMultiSign},
 *       {@code getPayMultiSignList}, {@code getPayMultiSignAddressList},
 *       {@code payMultiSignDetails})</li>
 *   <li>bridge peg in/out ({@code processPegIn}, {@code processPegOut})</li>
 *   <li>cross-chain anchors ({@code getAnchors})</li>
 *   <li>PoS consensus ({@code stakeDeposit}, {@code activateValidator},
 *       {@code getValidators}, {@code getBaseFee}, {@code setValidatorKey},
 *       {@code getValidatorKey}, {@code submitAttestation},
 *       {@code getAttestations}, {@code submitSlashingProof},
 *       {@code processWithdrawal}, {@code requestValidatorExit})</li>
 *   <li>extended output queries ({@code getOutputByKey},
 *       {@code getOutputsHistory})</li>
 * </ul>
 */
@RestController
@RequestMapping("/")
public class DispatcherController extends BaseDispatcherController {

	private static final Logger logger = LoggerFactory.getLogger(DispatcherController.class);

	@Autowired
	private PayMultiSignService payMultiSignService;
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

	@Autowired
	private net.bigtangle.server.service.GhostService ghostService;

	@Override
	protected String getChainName() {
		return "Bigtangle";
	}

	@Override
	protected boolean handleLayerSpecific(ReqCmd reqCmd, byte[] bodyByte, HttpServletResponse httpServletResponse,
			Stopwatch watch, BlockStoreInterface store, String reqCmdName) throws Exception {
		switch (reqCmd) {
		case processPegIn: {
			if (bridgeService == null) {
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.ErrorResponse.create(503), watch, reqCmdName);
				return true;
			}
			// Accept a SIGNED transaction that spends the source UTXO and
			// pays the vault. BridgeService verifies the input scriptSig
			// (ownership proof) before anything is locked — a raw outpoint +
			// beneficiary is never enough to lock someone else's UTXO.
			Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(bodyByte);
			bridgeService.processPegIn(tx, store);
			this.outPrintJSONString(httpServletResponse,
					new net.bigtangle.response.OkResponse(), watch, reqCmdName);
			return true;
		}
		case processPegOut: {
			if (bridgeService == null) {
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.ErrorResponse.create(503), watch, reqCmdName);
				return true;
			}
			// Process peg-out for confirmed anchors with an embedded burn.
			// Anchors are keyed by their L1 chain id (NOT this node's own
			// chain id, which on L0 is always "L0") — so iterate every
			// confirmed anchor; processPegOut is idempotent (already-spent
			// vaults are skipped), making this a safe manual retry for
			// previously-failed peg-outs (F7).
			int attempted = 0;
			for (net.bigtangle.server.data.AnchorRecord anchor : store.getAllAnchors()) {
				if (anchor.isConfirmed() && anchor.getBurnJson() != null && !anchor.getBurnJson().isEmpty()) {
					bridgeService.processPegOut(anchor, store);
					attempted++;
				}
			}
			logger.info("processPegOut: attempted {} confirmed anchor burns", attempted);
			this.outPrintJSONString(httpServletResponse,
					new net.bigtangle.response.OkResponse(), watch, reqCmdName);
			return true;
		}
		case getOutputsHistory: {
			outputHistory(bodyByte, httpServletResponse, watch, store);
			return true;
		}
		case searchTokens: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			GetTokensResponse response = tokensService.searchTokens((String) request.get("name"), store);
			this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			return true;
		}
		case searchWebTokens: {
			AbstractResponse response = tokensService.getWebTokensList(store);
			this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			return true;
		}
		case searchExchangeTokens: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			String keyword = request.get("name") != null ? request.get("name").toString()
					: request.get("keyword") != null ? request.get("keyword").toString() : null;
			GetTokensResponse response = tokensService.searchTokensByNameOrId(keyword, store);
			this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			return true;
		}
		case launchPayMultiSign: {
			this.payMultiSignService.launchPayMultiSign(bodyByte, store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
		case payMultiSign: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			AbstractResponse response = this.payMultiSignService.payMultiSign(request, store);
			this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			return true;
		}
		case getPayMultiSignList: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			List<String> keyStrHex000 = Json.jsonmapper().readValue(reqStr, List.class);
			AbstractResponse response = this.payMultiSignService.getPayMultiSignList(keyStrHex000, store);
			this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			return true;
		}
		case getPayMultiSignAddressList: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			String orderid = (String) request.get("orderid");
			AbstractResponse response = this.payMultiSignService.getPayMultiSignAddressList(orderid, store);
			this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			return true;
		}
		case payMultiSignDetails: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			String orderid = (String) request.get("orderid");
			AbstractResponse response = this.payMultiSignService.getPayMultiSignDetails(orderid, store);
			this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			return true;
		}
		case getOutputByKey: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			String hexStr = (String) request.get("hexStr");
			AbstractResponse response = walletService.getOutputsWithHexStr(hexStr, store);
			this.outPrintJSONString(httpServletResponse, response, watch, reqCmdName);
			return true;
		}
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
			return true;
		}
		case submitAttestation: {
			AttestationData att = Json.jsonmapper().readValue(bodyByte, AttestationData.class);
			casperService.processVote(att, store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
		case getAttestations: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			long slot = Long.parseLong(request.getOrDefault("slot", "0").toString());
			List<AttestationData> attestations = store.getAttestationsForSlot(slot);
			Map<String, Object> result = new HashMap<>();
			result.put("attestations", attestations);
			this.outPrintJSONString(httpServletResponse,
					net.bigtangle.response.GetStringResponse.create(
							Json.jsonmapper().writeValueAsString(result)), watch, reqCmdName);
			return true;
		}
		case processWithdrawal: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			long epoch = Long.parseLong(request.getOrDefault("epoch", "0").toString());
			stakeService.processWithdrawals(epoch, store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
		case requestValidatorExit: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			String pubkeyHex = (String) request.get("pubkey");
			String signatureHex = (String) request.get("signature");
			// The validator MUST prove key ownership with a signature; the
			// exit travels as a consensus BLOCKTYPE_EXIT block.
			if (signatureHex == null || signatureHex.isEmpty()) {
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.ErrorResponse.create(403), watch, reqCmdName);
				return true;
			}
			stakeService.submitExit(Utils.HEX.decode(pubkeyHex),
					Utils.HEX.decode(signatureHex), store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
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
						net.bigtangle.response.ErrorResponse.create(400), watch, reqCmdName);
				return true;
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
						net.bigtangle.response.ErrorResponse.create(403), watch, reqCmdName);
				return true;
			}
			boolean doubleVote = net.bigtangle.server.service.SlashingService.isDoubleVote(att1, att2);
			boolean surround = net.bigtangle.server.service.SlashingService.isSurroundVote(att1, att2);
			if (!doubleVote && !surround) {
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.ErrorResponse.create(400), watch, reqCmdName);
				return true;
			}
			stakeService.submitSlashing(att1, att2, store);
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
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
						net.bigtangle.response.ErrorResponse.create(403), watch, reqCmdName);
				return true;
			}
			String pubkeyHex = (String) request.get("pubkey");
			PQKey configuredKey = validatorDutyService.getValidatorKey();
			if (configuredKey == null) {
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.ErrorResponse.create(403), watch, reqCmdName);
				return true;
			}
			if (!Utils.HEX.encode(configuredKey.getPublicKeyBytes()).equals(pubkeyHex)) {
				this.outPrintJSONString(httpServletResponse,
						net.bigtangle.response.ErrorResponse.create(403), watch, reqCmdName);
				return true;
			}
			PQKey depositKey = configuredKey;
			String amountStr = (String) request.get("amount");
			java.math.BigInteger amount = new java.math.BigInteger(amountStr);
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
		case getValidators: {
			List<StakeRecord> validators = store.getActiveStakeDeposits();
			Map<String, Object> result = new HashMap<>();
			result.put("validators", validators);
			this.outPrintJSONString(httpServletResponse,
					net.bigtangle.response.GetStringResponse.create(
							Json.jsonmapper().writeValueAsString(result)), watch, reqCmdName);
			return true;
		}
		case getOptimisticFinality: {
			// Advisory, read-only: the confirmed head's current vote weight vs
			// total active stake, plus the justified/finalized checkpoints.
			// No consensus effect — the FFG finality rules are unchanged.
			net.bigtangle.response.OptimisticFinalityResponse resp =
					net.bigtangle.response.OptimisticFinalityResponse.create();
			net.bigtangle.core.TXReward head = store.getMaxConfirmedReward();
			if (head != null && head.getBlockHash() != null) {
				resp.setHeadBlockHash(head.getBlockHash().toString());
				resp.setChainLength(head.getChainLength());
				long weight = ghostService.branchVoteWeight(head.getBlockHash(), store);
				java.math.BigInteger total = stakeService.getTotalActiveStake(store);
				resp.setHeadVoteWeight(String.valueOf(weight));
				resp.setTotalStake(total.toString());
				resp.setSupermajority(total.signum() > 0 && java.math.BigInteger.valueOf(weight)
						.multiply(java.math.BigInteger.valueOf(3))
						.compareTo(total.multiply(java.math.BigInteger.valueOf(2))) >= 0);
			}
			CasperService.Checkpoint justified = casperService.getJustifiedCheckpoint();
			if (justified != null) {
				resp.setJustifiedEpoch(justified.getEpoch());
				resp.setJustifiedBlockHash(
						justified.getBlockHash() != null ? justified.getBlockHash().toString() : null);
			}
			CasperService.Checkpoint finalized = casperService.getLastFinalizedCheckpoint(store);
			if (finalized != null) {
				resp.setFinalizedEpoch(finalized.getEpoch());
				resp.setFinalizedBlockHash(
						finalized.getBlockHash() != null ? finalized.getBlockHash().toString() : null);
			}
			this.outPrintJSONString(httpServletResponse, resp, watch, reqCmdName);
			return true;
		}
		case getBaseFee: {
			Map<String, Object> result = new HashMap<>();
			result.put("baseFee", feeService.getBaseFee());
			result.put("feeDefault", Coin.FEE_DEFAULT.getValue().longValue());
			this.outPrintJSONString(httpServletResponse,
					net.bigtangle.response.GetStringResponse.create(
							Json.jsonmapper().writeValueAsString(result)), watch, reqCmdName);
			return true;
		}
		case setValidatorKey: {
			String reqStr = new String(bodyByte, StandardCharsets.UTF_8);
			Map<String, Object> request = Json.jsonmapper().readValue(reqStr, Map.class);
			String keyHex = (String) request.get("privateKey");
			if (keyHex != null && !keyHex.isEmpty()) {
				PQKey key = PQKey.createNew();
			}
			this.outPrintJSONString(httpServletResponse, OkResponse.create(), watch, reqCmdName);
			return true;
		}
		case getValidatorKey: {
			Map<String, Object> result = new HashMap<>();
			result.put("configured", validatorDutyService.getValidatorKey() != null);
			PQKey key = validatorDutyService.getValidatorKey();
			if (key != null) {
				result.put("pubkey", Utils.HEX.encode(key.getPubKey()));
			}
			this.outPrintJSONString(httpServletResponse,
					net.bigtangle.response.GetStringResponse.create(
							Json.jsonmapper().writeValueAsString(result)), watch, reqCmdName);
			return true;
		}
		default:
			return false;
		}
	}

	@Override
	public boolean checkAuth(HttpServletRequest httprequest, BlockStoreInterface store) {
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
			// Layer 0 keeps the legacy auth behaviour: the session token is
			// treated as the raw sighash (Sha256Hash.wrap), whereas the
			// layer-1 nodes hash it again (Sha256Hash.of).
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
}
