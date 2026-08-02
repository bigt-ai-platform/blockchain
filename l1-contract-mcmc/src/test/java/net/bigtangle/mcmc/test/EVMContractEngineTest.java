package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.EVMTransactionInfo;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.evm.EVMAddressUtil;
import net.bigtangle.evm.EVMStateCodec;
import net.bigtangle.evm.EVMStateRoot;
import net.bigtangle.evm.Word;
import net.bigtangle.evm.WorldState;
import net.bigtangle.layer1.contract.EVMContractEngine;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.service.base.ServiceBaseConnect;

/**
 * Exercises the EVM contract engine through the real block/store pipeline:
 * deploys an EVM contract token, submits EVM deploy + call blocks, runs the
 * engine and checks the deterministic state root and world state.
 */
class EVMContractEngineTest extends AbstractIntegrationTest {

	private static final String CONTRACT_ID = "evm-contract-test-1";

	@Test
	void deployThenCallIsDeterministic() throws Exception {
		// 1. EVM contract token (direct store insert, no multisig)
		TokenKeyValues kvs = new TokenKeyValues();
		net.bigtangle.core.KeyValue kv = new net.bigtangle.core.KeyValue();
		kv.setKey("classname");
		kv.setValue(EVMContractEngine.CLASSNAME);
		kvs.addKeyvalue(kv);
		Token token = Token.buildSimpleTokenInfo(true, null, CONTRACT_ID, "EVMTest", "", 1, 0, BigInteger.ONE,
				false, kvs, false, "", "", TokenType.contract.ordinal(), 0, "", Sha256Hash.ZERO_HASH.toString());
		store.insertToken(Sha256Hash.ZERO_HASH, token);
		store.updateTokenConfirmed(Sha256Hash.ZERO_HASH, true);

		PQKey owner = PQKey.createNew();
		String base58 = owner.toAddress(networkParameters).toBase58();

		// 2. deploy block: init code copies a 6-byte runtime (SSTORE(0,42); STOP)
		byte[] init = new byte[] { 0x60, 0x06, 0x60, 0x0c, 0x60, 0x00, 0x39, 0x60, 0x06, 0x60, 0x00, (byte) 0xf3,
				0x60, 0x2a, 0x60, 0x00, 0x55, 0x00 };
		Block deployBlock = submitEVMBlock(owner, base58, CONTRACT_ID, null, init, 0, 1_000_000L, 0);

		// 3. first execution (deploy only)
		ServiceBaseConnect support = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		Block beacon = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Set<Sha256Hash> refs = new HashSet<>();
		refs.add(deployBlock.getHash());

		Contractresult firstPrev = Contractresult.firstContractresult();
		ContractExecutionResult r1 = new EVMContractEngine().executeContract(support, networkParameters, beacon,
				store, CONTRACT_ID, firstPrev, refs);
		assertNotNull(r1);

		WorldState ws1 = EVMStateCodec.deserialize(r1.getExtraData());
		net.bigtangle.evm.Address contract = net.bigtangle.evm.Rlp.createAddress(
				EVMAddressUtil.evmAddressFromBase58(base58), 0);
		assertTrue(ws1.getCode(contract).length > 0);
		Sha256Hash root1 = EVMStateRoot.compute(ws1);
		assertEquals(root1, EVMStateRoot.compute(EVMStateCodec.deserialize(r1.getExtraData())));

		// 4. persist result 1 so the second execution chains off it
		r1.setConfirmed(true);
		store.insertContractResult(r1, net.bigtangle.server.service.base.ServiceBaseConfirmation
				.evmContractResultKey(beacon.getHash(), CONTRACT_ID));
		store.updateContractresultChainlength(net.bigtangle.server.service.base.ServiceBaseConfirmation
				.evmContractResultKey(beacon.getHash(), CONTRACT_ID), 1);
		store.updateContractResultConfirmed(net.bigtangle.server.service.base.ServiceBaseConfirmation
				.evmContractResultKey(beacon.getHash(), CONTRACT_ID), true);

		Contractresult lastConfirmed = store.getMaxConfirmedContractresult(CONTRACT_ID);
		assertNotNull(lastConfirmed);

		// 5. call block: invoke the deployed contract (executes SSTORE(0,42))
		Block callBlock = submitEVMBlock(owner, base58, CONTRACT_ID, contract.toHex(), new byte[0], 0, 1_000_000L, 1);

		Set<Sha256Hash> refs2 = new HashSet<>();
		refs2.add(callBlock.getHash());
		Block beacon2 = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		ContractExecutionResult r2 = new EVMContractEngine().executeContract(support, networkParameters, beacon2,
				store, CONTRACT_ID, lastConfirmed, refs2);
		assertNotNull(r2);

		WorldState ws2 = EVMStateCodec.deserialize(r2.getExtraData());
		assertEquals(Word.of(42), ws2.getStorage(contract).get(Word.ZERO));
		// determinism: same inputs -> same root
		ContractExecutionResult r2b = new EVMContractEngine().executeContract(support, networkParameters, beacon2,
				store, CONTRACT_ID, lastConfirmed, refs2);
		assertEquals(EVMStateRoot.compute(ws2),
				EVMStateRoot.compute(EVMStateCodec.deserialize(r2b.getExtraData())));
	}

	private Block submitEVMBlock(PQKey owner, String base58, String contractId, String to, byte[] data, long value,
			long gasLimit, long nonce) throws Exception {
		EVMTransactionInfo info = new EVMTransactionInfo(contractId, base58, to, BigInteger.valueOf(value), data,
				gasLimit, BigInteger.ZERO, nonce, net.bigtangle.params.NetworkParameters.BIGTANGLE_TOKENID_STRING,
				false);
		Transaction tx = new Transaction(networkParameters);
		tx.setData(info.toByteArray());
		tx.setDataClassName("EVMTransactionInfo");
		Sha256Hash sighash = tx.getHash();
		SignatureBundle sig = owner.sign(sighash, null);
		tx.setDataSignature(sig.serialize());

		// build the EVM block directly (no mempool / fee required for value 0)
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block block = UtilsTest.createBlock(networkParameters, predecessor, predecessor);
		block.addTransaction(tx);
		block.setBlockType(info.isDeploy() ? net.bigtangle.core.BlockType.BLOCKTYPE_EVM_DEPLOY
				: net.bigtangle.core.BlockType.BLOCKTYPE_EVM_CALL);
		block = adjustSolve(block);
		blockGraph.addBlock(block, true, store);
		return block;
	}
}
