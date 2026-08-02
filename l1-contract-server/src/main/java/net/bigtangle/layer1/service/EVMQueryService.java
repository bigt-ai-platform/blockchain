package net.bigtangle.layer1.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.evm.Address;
import net.bigtangle.evm.BlockContext;
import net.bigtangle.evm.EVMAddressUtil;
import net.bigtangle.evm.EVMExecutionResult;
import net.bigtangle.evm.EVMLog;
import net.bigtangle.evm.EVMStateCodec;
import net.bigtangle.evm.EVMTxReceipt;
import net.bigtangle.evm.Message;
import net.bigtangle.evm.MinimalEVMInterpreter;
import net.bigtangle.evm.Word;
import net.bigtangle.evm.WorldState;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Read-only access to the EVM world state and receipts for the Layer-1 EVM
 * contract. The world state is reconstructed deterministically from the latest
 * confirmed {@code Contractresult} blob (the engine's {@code extraData}).
 */
@Service
public class EVMQueryService {

	@Autowired
	private net.bigtangle.server.service.StoreService storeService;

	public WorldState loadWorldState(String contractTokenid) throws BlockStoreException {
		BlockStoreInterface store = storeService.getStore();
		try {
			Contractresult last = store.getMaxConfirmedContractresult(contractTokenid);
			return worldStateFrom(last);
		} finally {
			store.close();
		}
	}

	public WorldState worldStateFrom(Contractresult last) {
		if (last == null || last.getContractExecutionResult() == null) {
			return new WorldState();
		}
		try {
			net.bigtangle.core.ContractExecutionResult res = new net.bigtangle.core.ContractExecutionResult()
					.parseChecked(last.getContractExecutionResult());
			if (res.getExtraData() != null) {
				return EVMStateCodec.deserialize(res.getExtraData());
			}
		} catch (RuntimeException e) {
			// fall through to empty state
		}
		return new WorldState();
	}

	public String getBalance(String contractTokenid, String evmAddressHex) throws BlockStoreException {
		WorldState ws = loadWorldState(contractTokenid);
		return ws.getBalance(Address.fromHex(evmAddressHex)).toString(16);
	}

	public String getNonce(String contractTokenid, String evmAddressHex) throws BlockStoreException {
		WorldState ws = loadWorldState(contractTokenid);
		return Long.toString(ws.getAccount(Address.fromHex(evmAddressHex)) != null
				? ws.getAccount(Address.fromHex(evmAddressHex)).getNonce() : 0L);
	}

	public String getStorageAt(String contractTokenid, String evmAddressHex, String slotHex) throws BlockStoreException {
		WorldState ws = loadWorldState(contractTokenid);
		Word value = ws.getStorage(Address.fromHex(evmAddressHex)).get(Word.fromBytes(hexToBytes(slotHex)));
		return value.toBigInteger().toString(16);
	}

	public String getCode(String contractTokenid, String evmAddressHex) throws BlockStoreException {
		WorldState ws = loadWorldState(contractTokenid);
		return hex(ws.getCode(Address.fromHex(evmAddressHex)));
	}

	public String getStateRoot(String contractTokenid) throws BlockStoreException {
		WorldState ws = loadWorldState(contractTokenid);
		return net.bigtangle.evm.EVMStateRoot.compute(ws).toString();
	}

	/** Latest confirmed EVM block height (= the confirmed Contractresult chainlength). */
	public String getBlockNumber(String contractTokenid) throws BlockStoreException {
		BlockStoreInterface store = storeService.getStore();
		try {
			Contractresult last = store.getMaxConfirmedContractresult(contractTokenid);
			return last == null ? "0" : Long.toString(last.getChainlength());
		} finally {
			store.close();
		}
	}

	/** Read-only execution: runs the message against a copy of the world state. */
	public String call(String contractTokenid, String fromBase58, String toHex, String dataHex, String valueHex)
			throws BlockStoreException {
		WorldState ws = loadWorldState(contractTokenid);
		Address from = EVMAddressUtil.evmAddressFromBase58(fromBase58);
		Address to = Address.fromHex(toHex);
		byte[] data = dataHex == null || dataHex.isEmpty() ? new byte[0] : hexToBytes(dataHex);
		BigInteger value = valueHex == null || valueHex.isEmpty() ? BigInteger.ZERO : new BigInteger(valueHex, 16);
		Message msg = Message.call(from, from, to, value, data, 30_000_000L, Word.ZERO);
		EVMExecutionResult res = new MinimalEVMInterpreter().execute(msg, ws, BlockContext.createDefault(0, 0));
		return hex(res.getReturnData());
	}

	public EVMTxReceipt getReceipt(String contractTokenid, String evmBlockHashHex) throws BlockStoreException {
		BlockStoreInterface store = storeService.getStore();
		try {
			byte[] receiptBytes = store.getEVMReceipt(Sha256Hash.wrap(evmBlockHashHex));
			return receiptBytes == null ? null : EVMTxReceipt.parse(receiptBytes);
		} finally {
			store.close();
		}
	}

	public List<EVMLog> getLogs(String contractTokenid, String evmAddressHex) throws BlockStoreException {
		List<EVMLog> logs = new ArrayList<>();
		Address filter = evmAddressHex == null || evmAddressHex.isEmpty() ? null : Address.fromHex(evmAddressHex);
		BlockStoreInterface store = storeService.getStore();
		try {
			for (byte[] receiptBytes : store.getEVMReceiptsByToken(contractTokenid)) {
				EVMTxReceipt receipt = EVMTxReceipt.parse(receiptBytes);
				for (EVMLog log : receipt.getLogs()) {
					if (filter == null || filter.equals(log.getAddress())) {
						logs.add(log);
					}
				}
			}
		} finally {
			store.close();
		}
		return logs;
	}

	private static byte[] hexToBytes(String hex) {
		if (hex == null) {
			return new byte[0];
		}
		String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
		if ((h.length() & 1) != 0) {
			h = "0" + h;
		}
		return net.bigtangle.core.Utils.HEX.decode(h);
	}

	private static String hex(byte[] bytes) {
		return "0x" + net.bigtangle.core.Utils.HEX.encode(bytes);
	}
}
