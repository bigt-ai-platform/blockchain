package net.bigtangle.evm;

import static net.bigtangle.evm.TestAddresses.addr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

class EVMTxProcessorTest {

	private static final Word GAS_PRICE = Word.ZERO;

	private EVMTxProcessor processor() {
		return new EVMTxProcessor(new MinimalEVMInterpreter(), BlockContext.createDefault(0, 1));
	}

	@Test
	void valueTransferBetweenAccounts() {
		WorldState ws = new WorldState();
		Address alice = addr("01");
		Address bob = addr("02");
		ws.addBalance(alice, BigInteger.valueOf(100));

		List<EVMTx> txs = List.of(
				new EVMTx(alice, bob, BigInteger.valueOf(40), new byte[0], 25_000, GAS_PRICE, 0));
		EVMBatchResult result = processor().process(txs, ws);

		assertTrue(result.getReceipts().get(0).isSuccess());
		assertEquals(BigInteger.valueOf(60), result.getWorldState().getBalance(alice));
		assertEquals(BigInteger.valueOf(40), result.getWorldState().getBalance(bob));
		assertEquals(21_000, result.getReceipts().get(0).getGasUsed());
	}

	@Test
	void contractDeployAndCall() {
		WorldState ws = new WorldState();
		Address alice = addr("aa");
		ws.addBalance(alice, BigInteger.valueOf(1000));

		// init code: copy 6-byte runtime to memory and return it
		byte[] init = new byte[] { 0x60, 0x06, 0x60, 0x0c, 0x60, 0x00, 0x39, 0x60, 0x06, 0x60, 0x00, (byte) 0xf3,
				0x60, 0x2a, 0x60, 0x00, 0x55, 0x00 };
		EVMTx deploy = new EVMTx(alice, null, BigInteger.ZERO, init, 200_000, GAS_PRICE, 0);

		EVMBatchResult result = processor().process(List.of(deploy), ws);
		assertTrue(result.getReceipts().get(0).isSuccess());

		Address contract = Rlp.createAddress(alice, 0);
		assertEquals(result.getReceipts().get(0).getContractAddress(), contract);
		byte[] runtime = result.getWorldState().getCode(contract);
		assertEquals(6, runtime.length);
		assertEquals(0x60, runtime[0] & 0xff);
		assertEquals(0x2a, runtime[1] & 0xff);

		// second tx: call the deployed contract with empty calldata -> runs SSTORE(0, 42)
		EVMTx call = new EVMTx(alice, contract, BigInteger.ZERO, new byte[0], 100_000, GAS_PRICE, 1);
		EVMBatchResult result2 = processor().process(List.of(call), result.getWorldState());
		assertTrue(result2.getReceipts().get(0).isSuccess());
		assertEquals(Word.of(42), result2.getWorldState().getStorage(contract).get(Word.ZERO));
	}

	@Test
	void failedCallRollsBackValueButKeepsNonce() {
		WorldState ws = new WorldState();
		Address alice = addr("aa");
		Address bob = addr("bb");
		ws.addBalance(alice, BigInteger.valueOf(100));

		// bob reverts any call: PUSH1 0 PUSH1 0 REVERT
		byte[] revertCode = new byte[] { 0x60, 0x00, 0x60, 0x00, (byte) 0xfd };
		ws.setCode(bob, revertCode);

		EVMTx tx = new EVMTx(alice, bob, BigInteger.valueOf(50), new byte[0], 100_000, GAS_PRICE, 0);
		EVMBatchResult result = processor().process(List.of(tx), ws);

		assertFalse(result.getReceipts().get(0).isSuccess());
		assertEquals(BigInteger.valueOf(100), result.getWorldState().getBalance(alice));
		assertEquals(BigInteger.ZERO, result.getWorldState().getBalance(bob));
		assertEquals(1, result.getWorldState().getAccount(alice).getNonce());
	}

	@Test
	void invalidNonceRejected() {
		WorldState ws = new WorldState();
		Address alice = addr("01");
		ws.addBalance(alice, BigInteger.valueOf(100));

		EVMTx tx = new EVMTx(alice, addr("02"), BigInteger.ZERO, new byte[0], 25_000, GAS_PRICE, 5);
		EVMBatchResult result = processor().process(List.of(tx), ws);
		assertFalse(result.getReceipts().get(0).isSuccess());
		// no state change for a rejected transaction
		assertEquals(BigInteger.valueOf(100), result.getWorldState().getBalance(alice));
		assertEquals(0, result.getWorldState().getAccount(alice).getNonce());
	}

	@Test
	void deterministicStateRoot() {
		Address alice = addr("01");
		Address bob = addr("02");
		byte[] init = new byte[] { 0x60, 0x06, 0x60, 0x0c, 0x60, 0x00, 0x39, 0x60, 0x06, 0x60, 0x00, (byte) 0xf3,
				0x60, 0x2a, 0x60, 0x00, 0x55, 0x00 };
		List<EVMTx> txs = List.of(new EVMTx(alice, null, BigInteger.ZERO, init, 200_000, GAS_PRICE, 0),
				new EVMTx(alice, bob, BigInteger.valueOf(7), new byte[0], 25_000, GAS_PRICE, 1));

		WorldState ws1 = new WorldState();
		ws1.addBalance(alice, BigInteger.valueOf(1000));
		EVMBatchResult r1 = processor().process(txs, ws1);

		WorldState ws2 = new WorldState();
		ws2.addBalance(alice, BigInteger.valueOf(1000));
		EVMBatchResult r2 = processor().process(txs, ws2);

		assertEquals(r1.getStateRoot(), r2.getStateRoot());
		assertEquals(r1.getReceipts().size(), r2.getReceipts().size());
	}

	@Test
	void emptyBatchRootDeterministic() {
		WorldState ws1 = new WorldState();
		ws1.addBalance(addr("01"), BigInteger.valueOf(5));
		EVMBatchResult r1 = processor().process(List.of(), ws1);
		WorldState ws2 = new WorldState();
		ws2.addBalance(addr("01"), BigInteger.valueOf(5));
		EVMBatchResult r2 = processor().process(List.of(), ws2);
		assertEquals(r1.getStateRoot(), r2.getStateRoot());
	}
}
