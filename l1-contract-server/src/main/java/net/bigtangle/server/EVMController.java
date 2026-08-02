package net.bigtangle.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.evm.EVMLog;
import net.bigtangle.evm.EVMTxReceipt;
import net.bigtangle.layer1.service.EVMQueryService;

/**
 * EVM RPC endpoints for the Layer-1 contract chain. JSON-RPC-style:
 * {@code POST /evm/rpc} with {@code {"method": "...", "params": [...]}}.
 *
 * <p>Methods: {@code getBalance}, {@code getNonce}, {@code getStorageAt},
 * {@code getCode}, {@code getStateRoot}, {@code getBlockNumber},
 * {@code getReceipt}, {@code getLogs}, {@code call}, {@code getEVMAddress}.
 */
@RestController
@RequestMapping("/evm")
public class EVMController {

	@Autowired
	private EVMQueryService evmQueryService;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@SuppressWarnings("unchecked")
	@PostMapping("/rpc")
	public Map<String, Object> rpc(@RequestBody Map<String, Object> request) throws Exception {
		String method = (String) request.get("method");
		List<Object> params = request.get("params") == null ? List.of() : (List<Object>) request.get("params");
		Map<String, Object> result = new HashMap<>();
		String error = null;
		try {
			Object value = dispatch(method, params);
			result.put("result", value);
		} catch (Exception e) {
			error = e.getMessage();
		}
		result.put("jsonrpc", "2.0");
		result.put("id", request.get("id"));
		if (error != null) {
			result.put("error", error);
		}
		return result;
	}

	private Object dispatch(String method, List<Object> params) throws Exception {
		switch (method) {
		case "getBalance":
			return evmQueryService.getBalance(str(params, 0), str(params, 1));
		case "getNonce":
			return evmQueryService.getNonce(str(params, 0), str(params, 1));
		case "getStorageAt":
			return evmQueryService.getStorageAt(str(params, 0), str(params, 1), str(params, 2));
		case "getCode":
			return evmQueryService.getCode(str(params, 0), str(params, 1));
		case "getStateRoot":
			return evmQueryService.getStateRoot(str(params, 0));
		case "getBlockNumber":
			return evmQueryService.getBlockNumber(str(params, 0));
		case "getReceipt": {
			EVMTxReceipt receipt = evmQueryService.getReceipt(str(params, 0), str(params, 1));
			if (receipt == null) {
				return null;
			}
			return receiptToMap(receipt);
		}
		case "getLogs": {
			List<EVMLog> logs = evmQueryService.getLogs(str(params, 0),
					params.size() > 1 && params.get(1) != null ? str(params, 1) : "");
			return logs.stream().map(this::logToMap).toList();
		}
		case "call":
			return evmQueryService.call(str(params, 0), str(params, 1), str(params, 2), str(params, 3),
					params.size() > 4 ? str(params, 4) : "");
		case "getEVMAddress":
			return net.bigtangle.evm.EVMAddressUtil.evmAddressFromBase58(str(params, 0)).toHex();
		case "getTransactionCount":
			return evmQueryService.getNonce(str(params, 0), str(params, 1));
		default:
			throw new IllegalArgumentException("unknown EVM RPC method: " + method);
		}
	}

	private Map<String, Object> receiptToMap(EVMTxReceipt receipt) {
		Map<String, Object> map = new HashMap<>();
		map.put("status", receipt.getStatus());
		map.put("gasUsed", receipt.getGasUsed());
		map.put("cumulativeGasUsed", receipt.getCumulativeGasUsed());
		map.put("contractAddress", receipt.getContractAddress() == null ? null : receipt.getContractAddress().toHex());
		map.put("from", receipt.getFrom().toHex());
		map.put("to", receipt.getTo() == null ? null : receipt.getTo().toHex());
		map.put("returnData", "0x" + net.bigtangle.core.Utils.HEX.encode(receipt.getReturnData()));
		map.put("logs", receipt.getLogs().stream().map(this::logToMap).toList());
		return map;
	}

	private Map<String, Object> logToMap(EVMLog log) {
		Map<String, Object> map = new HashMap<>();
		map.put("address", log.getAddress().toHex());
		map.put("topics", log.getTopics().stream().map(t -> "0x" + net.bigtangle.core.Utils.HEX.encode(t.toBytes()))
				.toList());
		map.put("data", "0x" + net.bigtangle.core.Utils.HEX.encode(log.getData()));
		return map;
	}

	private static String str(List<Object> params, int index) {
		return params.size() > index && params.get(index) != null ? params.get(index).toString() : "";
	}
}
