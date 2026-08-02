package net.bigtangle.evm;

import static net.bigtangle.evm.TestAddresses.addr;
import static net.bigtangle.evm.TestAddresses.Bytecode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class MinimalEVMInterpreterTest {

	private static final long GAS = 1_000_000L;

	private EVMExecutionResult run(Address contract, byte[] code, byte[] calldata, WorldState ws) {
		ws.getOrCreateAccount(contract).setCode(code);
		Address sender = addr("11");
		Message msg = Message.call(sender, sender, contract, BigInteger.ZERO, calldata, GAS, Word.ZERO);
		return new MinimalEVMInterpreter().execute(msg, ws, BlockContext.createDefault(0, 0));
	}

	@Test
	void arithmeticAdd() {
		byte[] code = new Bytecode().push1(0x01).push1(0x02).op(0x01) // ADD
				.push1(0x00).op(0x52) // MSTORE(0, 3)
				.push1(0x20).push1(0x00).op(0xf3).build(); // RETURN(0, 32)
		EVMExecutionResult r = run(addr("aabb"), code, new byte[0], new WorldState());
		assertTrue(r.isSuccess());
		assertEquals(Word.of(3), Word.fromBytes(r.getReturnData()));
	}

	@Test
	void storageReadWrite() {
		byte[] code = new Bytecode().push1(0x2a).push1(0x00).op(0x55) // SSTORE(0, 42)
				.push1(0x00).op(0x54) // SLOAD(0)
				.push1(0x00).op(0x52) // MSTORE
				.push1(0x20).push1(0x00).op(0xf3).build(); // RETURN
		WorldState ws = new WorldState();
		EVMExecutionResult r = run(addr("aabb"), code, new byte[0], ws);
		assertTrue(r.isSuccess());
		assertEquals(Word.of(42), Word.fromBytes(r.getReturnData()));
		assertEquals(Word.of(42), ws.getStorage(addr("aabb")).get(Word.ZERO));
	}

	@Test
	void calldataLoad() {
		byte[] code = new Bytecode().push1(0x00).op(0x35) // CALLDATALOAD(0)
				.push1(0x00).op(0x52) // MSTORE
				.push1(0x20).push1(0x00).op(0xf3).build(); // RETURN
		byte[] calldata = new byte[32];
		calldata[31] = 0x2a;
		EVMExecutionResult r = run(addr("aabb"), code, calldata, new WorldState());
		assertTrue(r.isSuccess());
		assertEquals(Word.of(42), Word.fromBytes(r.getReturnData()));
	}

	@Test
	void callForwardsValueAndReturnData() {
		Address callee = addr("0000000000000000000000000000000000000002");
		Address caller = addr("0000000000000000000000000000000000000001");

		byte[] calleeCode = new Bytecode().push1(0x05).push1(0x00).op(0x52) // MSTORE(0, 5)
				.push1(0x20).push1(0x00).op(0xf3).build(); // RETURN(0, 32)

		byte[] callerCode = new Bytecode().push1(0x20).push1(0x40).push1(0x00).push1(0x00).push1(0x07)
				.push1(0x02).push2(0xffff).op(0xf1) // CALL(0xffff, 0x2, 7, 0,0, 0x40,0x20)
				.push1(0x00).push1(0x00).op(0x50) // POP success flag
				.push1(0x20).push1(0x40).op(0xf3).build(); // RETURN(0x40, 0x20)

		WorldState ws = new WorldState();
		ws.addBalance(caller, BigInteger.valueOf(10));
		ws.setCode(callee, calleeCode);
		EVMExecutionResult r = run(caller, callerCode, new byte[0], ws);

		assertTrue(r.isSuccess());
		assertEquals(Word.of(5), Word.fromBytes(r.getReturnData()));
		assertEquals(BigInteger.valueOf(3), ws.getBalance(caller));
		assertEquals(BigInteger.valueOf(7), ws.getBalance(callee));
	}

	@Test
	void revertReturnsReason() {
		// MSTORE(0, 0xdeadbeef); REVERT(0, 32)
		byte[] code = new Bytecode().push32(reason()).push1(0x00).op(0x52).push1(0x20).push1(0x00).op(0xfd)
				.build();
		EVMExecutionResult r = run(addr("aabb"), code, new byte[0], new WorldState());
		assertFalse(r.isSuccess());
		assertEquals(0xde, r.getReturnData()[28] & 0xff);
		assertEquals(0xad, r.getReturnData()[29] & 0xff);
		assertEquals(0xbe, r.getReturnData()[30] & 0xff);
		assertEquals(0xef, r.getReturnData()[31] & 0xff);
	}

	@Test
	void createFromContract() {
		Address factory = addr("0000000000000000000000000000000000000001");
		byte[] initCode = new byte[] { 0x60, 0x2a, 0x60, 0x00, 0x55, 0x00 }; // SSTORE(0,42); STOP
		// leading code is 17 bytes, so the embedded init code starts at offset 0x11
		byte[] code = new Bytecode().push1(0x06).push1(0x11).push1(0x00).op(0x39) // CODECOPY(0, 17, 6)
				.push1(0x06).push1(0x00).push1(0x00).op(0xf0) // CREATE(0, 0, 6)
				.push1(0x00).op(0x50).build(); // POP created address
		byte[] full = new byte[code.length + initCode.length];
		System.arraycopy(code, 0, full, 0, code.length);
		System.arraycopy(initCode, 0, full, code.length, initCode.length);

		WorldState ws = new WorldState();
		EVMExecutionResult r = run(factory, full, new byte[0], ws);
		assertTrue(r.isSuccess());
		Address created = Rlp.createAddress(factory, 0);
		assertEquals(Word.of(42), ws.getStorage(created).get(Word.ZERO));
	}

	@Test
	void staticCallCannotWriteStorage() {
		Address callee = addr("0000000000000000000000000000000000000002");
		Address caller = addr("0000000000000000000000000000000000000001");
		byte[] calleeCode = new Bytecode().push1(0x2a).push1(0x00).op(0x55).push1(0x00).push1(0x00).op(0xf3)
				.build(); // SSTORE(0,42); RETURN(0,0)
		byte[] callerCode = new Bytecode().push1(0x00).push1(0x00).push1(0x00).push1(0x00).push1(0x02)
				.push2(0xffff).op(0xfa) // STATICCALL(0xffff, 0x2, 0,0, 0,0)
				.push1(0x00).push1(0x00).op(0x50).push1(0x00).push1(0x00).op(0xf3).build();

		WorldState ws = new WorldState();
		ws.setCode(callee, calleeCode);
		EVMExecutionResult r = run(caller, callerCode, new byte[0], ws);
		assertTrue(r.isSuccess());
		assertEquals(Word.ZERO, ws.getStorage(callee).get(Word.ZERO));
	}

	private static byte[] reason() {
		byte[] out = new byte[32];
		out[28] = (byte) 0xde;
		out[29] = (byte) 0xad;
		out[30] = (byte) 0xbe;
		out[31] = (byte) 0xef;
		return out;
	}
}
