package net.bigtangle.evm;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A minimal but faithful deterministic EVM interpreter covering the core opcode
 * set needed to run compiled Solidity: arithmetic/comparison/bitwise, memory,
 * storage, environment/block context, jumps, logs, {@code CALL}/{@code CALLCODE}/
 * {@code DELEGATECALL}/{@code STATICCALL}, {@code CREATE}/{@code CREATE2},
 * {@code RETURN}/{@code REVERT} and {@code SELFDESTRUCT}.
 *
 * <p>Consensus-relevant properties:
 * <ul>
 * <li>Pure Java, no randomness, no wall-clock, no hash-map iteration order —
 *     identical inputs always produce identical outputs.</li>
 * <li>Gas is metered with a deterministic constant schedule (simplified
 *     Homestead-era constants; no refunds, no warm/cold access lists).</li>
 * <li>Value transfer for the top-level message is performed by the caller
 *     ({@link EVMTxProcessor}); opcode-level transfers snapshot and roll back
 *     the world state on failure.</li>
 * </ul>
 */
public final class MinimalEVMInterpreter implements EVMInterpreter {

	// Gas constants (simplified deterministic schedule)
	private static final long GZERO = 0;
	private static final long GBASE = 2;
	private static final long GVERYLOW = 3;
	private static final long GLOW = 5;
	private static final long GMID = 8;
	private static final long GHIGH = 10;
	private static final long GEXP = 10;
	private static final long GEXPBYTE = 50;
	private static final long GMEMORY = 3;
	private static final long GQUADCOEFF = 512;
	private static final long GCOPY = 3;
	private static final long GBLOCKHASH = 20;
	private static final long GBALANCE = 700;
	private static final long GEXTCODE = 700;
	private static final long GSLOAD = 800;
	private static final long GSSTORE_SET = 20000;
	private static final long GSSTORE_RESET = 5000;
	private static final long GSELFDESTRUCT = 5000;
	private static final long GCREATE = 32000;
	private static final long GCODEDEPOSIT = 200;
	private static final long GCALL = 700;
	private static final long GCALLVALUE = 9000;
	private static final long GCALLSTIPEND = 2300;
	private static final long GNEWACCOUNT = 25000;
	private static final long GSHA3 = 30;
	private static final long GSHA3WORD = 6;
	private static final long GLOG = 375;
	private static final long GLOGTOPIC = 375;
	private static final long GLOGDATA = 8;

	private static final int MAX_STACK = 1024;
	private static final int MAX_CALL_DEPTH = 1024;
	private static final int MAX_CODE_DEPOSIT = 24576;

	// Opcodes
	private static final int STOP = 0x00;
	private static final int ADD = 0x01, MUL = 0x02, SUB = 0x03, DIV = 0x04, SDIV = 0x05, MOD = 0x06, SMOD = 0x07,
			ADDMOD = 0x08, MULMOD = 0x09, EXP = 0x0a, SIGNEXTEND = 0x0b;
	private static final int LT = 0x10, GT = 0x11, SLT = 0x12, SGT = 0x13, EQ = 0x14, ISZERO = 0x15, AND = 0x16,
			OR = 0x17, XOR = 0x18, NOT = 0x19, BYTE = 0x1a, SHL = 0x1b, SHR = 0x1c, SAR = 0x1d;
	private static final int SHA3 = 0x20;
	private static final int ADDRESS = 0x30, BALANCE = 0x31, ORIGIN = 0x32, CALLER = 0x33, CALLVALUE = 0x34,
			CALLDATALOAD = 0x35, CALLDATASIZE = 0x36, CALLDATACOPY = 0x37, CODESIZE = 0x38, CODECOPY = 0x39,
			GASPRICE = 0x3a, EXTCODESIZE = 0x3b, EXTCODECOPY = 0x3c, RETURNDATASIZE = 0x3d, RETURNDATACOPY = 0x3e,
			EXTCODEHASH = 0x3f;
	private static final int BLOCKHASH = 0x40, COINBASE = 0x41, TIMESTAMP = 0x42, NUMBER = 0x43, DIFFICULTY = 0x44,
			GASLIMIT = 0x45, CHAINID = 0x46, SELFBALANCE = 0x47, BASEFEE = 0x48;
	private static final int POP = 0x50, MLOAD = 0x51, MSTORE = 0x52, MSTORE8 = 0x53, SLOAD = 0x54, SSTORE = 0x55,
			JUMP = 0x56, JUMPI = 0x57, PC = 0x58, MSIZE = 0x59, GAS = 0x5a, JUMPDEST = 0x5b;
	private static final int PUSH1 = 0x60, PUSH32 = 0x7f;
	private static final int DUP1 = 0x80, DUP16 = 0x8f;
	private static final int SWAP1 = 0x90, SWAP16 = 0x9f;
	private static final int LOG0 = 0xa0, LOG4 = 0xa4;
	private static final int CREATE = 0xf0, CALL = 0xf1, CALLCODE = 0xf2, RETURN = 0xf3, DELEGATECALL = 0xf4,
			CREATE2 = 0xf5, STATICCALL = 0xfa, REVERT = 0xfd, INVALID = 0xfe, SELFDESTRUCT = 0xff;

	@Override
	public EVMExecutionResult execute(Message message, WorldState worldState, BlockContext blockContext) {
		List<EVMLog> logs = new ArrayList<>();
		return new Frame(message, worldState, blockContext, logs, 0).run();
	}

	private static final class Frame {

		private final Message msg;
		private final WorldState ws;
		private final BlockContext bc;
		private final List<EVMLog> logs;
		private final int depth;

		private final byte[] code;
		private final byte[] calldata;
		private final boolean[] validJumps;

		private final ArrayList<Word> stack = new ArrayList<>();
		private final Memory memory = new Memory();

		private long gas;
		private int pc = 0;
		private byte[] returnData = new byte[0];
		private byte[] lastReturnData = new byte[0];

		Frame(Message msg, WorldState ws, BlockContext bc, List<EVMLog> logs, int depth) {
			this.msg = msg;
			this.ws = ws;
			this.bc = bc;
			this.logs = logs;
			this.depth = depth;
			this.gas = msg.getGas();
			if (msg.isCreate()) {
				this.code = msg.getData();
			} else {
				this.code = ws.getCode(msg.getCodeAddress());
			}
			this.calldata = msg.getData();
			this.validJumps = new boolean[code.length];
			for (int i = 0; i < code.length; i++) {
				if ((code[i] & 0xff) == JUMPDEST) {
					validJumps[i] = true;
				}
			}
		}

		EVMExecutionResult run() {
			try {
				boolean halted = false;
				while (!halted && pc < code.length) {
					int op = code[pc++] & 0xff;
					switch (op) {
						case STOP:
							halted = true;
							break;

						// arithmetic
						case ADD: {
							Word b = pop();
							Word a = pop();
							push(a.add(b));
							break;
						}
						case MUL: {
							Word b = pop();
							Word a = pop();
							push(a.mul(b));
							break;
						}
						case SUB: {
							Word b = pop();
							Word a = pop();
							push(a.sub(b));
							break;
						}
						case DIV: {
							Word b = pop();
							Word a = pop();
							push(a.div(b));
							break;
						}
						case SDIV: {
							Word b = pop();
							Word a = pop();
							if (b.isZero()) {
								push(Word.ZERO);
							} else {
								push(Word.fromSigned(a.toSigned().divide(b.toSigned())));
							}
							break;
						}
						case MOD: {
							Word b = pop();
							Word a = pop();
							push(a.mod(b));
							break;
						}
						case SMOD: {
							Word b = pop();
							Word a = pop();
							if (b.isZero()) {
								push(Word.ZERO);
							} else {
								BigInteger sa = a.toSigned();
								BigInteger sb = b.toSigned();
								BigInteger q = sa.divide(sb);
								push(Word.fromSigned(sa.subtract(q.multiply(sb))));
							}
							break;
						}
						case ADDMOD: {
							Word n = pop();
							Word b = pop();
							Word a = pop();
							if (n.isZero()) {
								push(Word.ZERO);
							} else {
								push(Word.of(a.toBigInteger().add(b.toBigInteger()).mod(n.toBigInteger())));
							}
							break;
						}
						case MULMOD: {
							Word n = pop();
							Word b = pop();
							Word a = pop();
							if (n.isZero()) {
								push(Word.ZERO);
							} else {
								push(Word.of(a.toBigInteger().multiply(b.toBigInteger()).mod(n.toBigInteger())));
							}
							break;
						}
						case EXP: {
							Word e = pop();
							Word a = pop();
							int byteLen = (e.toBigInteger().bitLength() + 7) / 8;
							charge(GEXP + (long) byteLen * GEXPBYTE);
							push(a.exp(e));
							break;
						}
						case SIGNEXTEND: {
							Word b = pop();
							Word x = pop();
							push(signExtend(b, x));
							break;
						}

						// comparison / bitwise
						case LT: {
							Word b = pop();
							Word a = pop();
							push(a.compareTo(b) < 0 ? Word.ONE : Word.ZERO);
							break;
						}
						case GT: {
							Word b = pop();
							Word a = pop();
							push(a.compareTo(b) > 0 ? Word.ONE : Word.ZERO);
							break;
						}
						case SLT: {
							Word b = pop();
							Word a = pop();
							push(a.toSigned().compareTo(b.toSigned()) < 0 ? Word.ONE : Word.ZERO);
							break;
						}
						case SGT: {
							Word b = pop();
							Word a = pop();
							push(a.toSigned().compareTo(b.toSigned()) > 0 ? Word.ONE : Word.ZERO);
							break;
						}
						case EQ: {
							Word b = pop();
							Word a = pop();
							push(a.equals(b) ? Word.ONE : Word.ZERO);
							break;
						}
						case ISZERO: {
							push(pop().isZero() ? Word.ONE : Word.ZERO);
							break;
						}
						case AND: {
							Word b = pop();
							Word a = pop();
							push(a.and(b));
							break;
						}
						case OR: {
							Word b = pop();
							Word a = pop();
							push(a.or(b));
							break;
						}
						case XOR: {
							Word b = pop();
							Word a = pop();
							push(a.xor(b));
							break;
						}
						case NOT:
							push(pop().not());
							break;
						case BYTE: {
							Word i = pop();
							Word x = pop();
							if (i.toBigInteger().compareTo(BigInteger.valueOf(32)) >= 0) {
								push(Word.ZERO);
							} else {
								push(Word.of(x.toBigInteger().shiftRight((31 - i.intValue()) * 8)
										.and(BigInteger.valueOf(0xff))));
							}
							break;
						}
						case SHL: {
							Word s = pop();
							Word v = pop();
							push(v.shl(s));
							break;
						}
						case SHR: {
							Word s = pop();
							Word v = pop();
							push(v.shr(s));
							break;
						}
						case SAR: {
							Word s = pop();
							Word v = pop();
							push(v.sar(s));
							break;
						}

						// keccak
						case SHA3: {
							Word offsetW = pop();
							Word sizeW = pop();
							int offset = toInt(offsetW);
							int size = toInt(sizeW);
							int words = (size + 31) / 32;
							charge(GSHA3 + (long) words * GSHA3WORD);
							byte[] mem = ensureMemory(offset, size);
							push(Word.fromBytes(Keccak.hash(Arrays.copyOfRange(mem, offset, offset + size))));
							break;
						}

						// environment
						case ADDRESS:
							push(Word.fromBytes(msg.getAddress().toBytes()));
							break;
						case BALANCE: {
							Word a = pop();
							charge(GBALANCE);
							push(Word.of(ws.getBalance(Address.fromLast20Bytes(a.toBytes()))));
							break;
						}
						case ORIGIN:
							push(Word.fromBytes(msg.getOrigin().toBytes()));
							break;
						case CALLER:
							push(Word.fromBytes(msg.getSender().toBytes()));
							break;
						case CALLVALUE:
							push(Word.of(msg.getValue()));
							break;
						case CALLDATALOAD: {
							long offset = toLong(pop());
							byte[] data = new byte[32];
							for (int i = 0; i < 32; i++) {
								long idx = offset + i;
								data[i] = idx < calldata.length ? calldata[(int) idx] : 0;
							}
							push(Word.fromBytes(data));
							break;
						}
						case CALLDATASIZE:
							push(Word.of(calldata.length));
							break;
						case CALLDATACOPY: {
							Word m = pop();
							Word d = pop();
							Word s = pop();
							int size = toInt(s);
							charge(GVERYLOW + GCOPY * words(size));
							copyFrom(calldata, toInt(m), toLong(d), size);
							break;
						}
						case CODESIZE:
							push(Word.of(code.length));
							break;
						case CODECOPY: {
							Word m = pop();
							Word d = pop();
							Word s = pop();
							int size = toInt(s);
							charge(GVERYLOW + GCOPY * words(size));
							copyFrom(code, toInt(m), toLong(d), size);
							break;
						}
						case GASPRICE:
							push(msg.getGasPrice());
							break;
						case EXTCODESIZE: {
							Word a = pop();
							charge(GEXTCODE);
							push(Word.of(ws.getCode(Address.fromLast20Bytes(a.toBytes())).length));
							break;
						}
						case EXTCODECOPY: {
							Word a = pop();
							Word m = pop();
							Word d = pop();
							Word s = pop();
							int size = toInt(s);
							charge(GEXTCODE + GCOPY * words(size));
							byte[] src = ws.getCode(Address.fromLast20Bytes(a.toBytes()));
							copyFrom(src, toInt(m), toLong(d), size);
							break;
						}
						case RETURNDATASIZE:
							push(Word.of(lastReturnData.length));
							break;
						case RETURNDATACOPY: {
							Word m = pop();
							Word d = pop();
							Word s = pop();
							int memOffset = toInt(m);
							long dataOffset = toLong(d);
							int size = toInt(s);
							if (dataOffset + size > lastReturnData.length) {
								throw new HaltException();
							}
							charge(GVERYLOW + GCOPY * words(size));
							copyFrom(lastReturnData, memOffset, dataOffset, size);
							break;
						}
						case EXTCODEHASH: {
							Word a = pop();
							charge(GEXTCODE);
							EVMAccount account = ws.getAccount(Address.fromLast20Bytes(a.toBytes()));
							if (account == null || !account.hasCode()) {
								push(Word.ZERO);
							} else {
								push(Word.fromBytes(account.getCodeHash().getBytes()));
							}
							break;
						}

						// block context
						case BLOCKHASH: {
							long height = toLong(pop());
							charge(GBLOCKHASH);
							push(bc.getBlockHash(height));
							break;
						}
						case COINBASE:
							push(bc.getCoinbase());
							break;
						case TIMESTAMP:
							push(Word.of(bc.getTimestamp()));
							break;
						case NUMBER:
							push(Word.of(bc.getNumber()));
							break;
						case DIFFICULTY:
							push(bc.getDifficulty());
							break;
						case GASLIMIT:
							push(Word.of(bc.getGasLimit()));
							break;
						case CHAINID:
							push(Word.of(bc.getChainId()));
							break;
						case SELFBALANCE:
							push(Word.of(ws.getBalance(msg.getAddress())));
							break;
						case BASEFEE:
							push(bc.getBaseFee());
							break;

						// memory / stack / flow
						case POP:
							pop();
							break;
						case MLOAD: {
							int offset = toInt(pop());
							charge(GVERYLOW);
							byte[] mem = ensureMemory(offset, 32);
							push(Word.fromBytes(Arrays.copyOfRange(mem, offset, offset + 32)));
							break;
						}
						case MSTORE: {
							Word offset = pop();
							Word value = pop();
							int o = toInt(offset);
							charge(GVERYLOW);
							byte[] mem = ensureMemory(o, 32);
							System.arraycopy(value.toBytes(), 0, mem, o, 32);
							break;
						}
						case MSTORE8: {
							Word offset = pop();
							Word value = pop();
							int o = toInt(offset);
							charge(GVERYLOW);
							byte[] mem = ensureMemory(o, 1);
							mem[o] = (byte) value.intValue();
							break;
						}
						case SLOAD: {
							Word key = pop();
							charge(GSLOAD);
							push(ws.getStorage(msg.getAddress()).get(key));
							break;
						}
						case SSTORE: {
							Word key = pop();
							Word value = pop();
							if (msg.isStatic()) {
								throw new HaltException();
							}
							Word current = ws.getStorage(msg.getAddress()).get(key);
							charge(current.isZero() ? GSSTORE_SET : GSSTORE_RESET);
							ws.getStorage(msg.getAddress()).put(key, value);
							break;
						}
						case JUMP:
							jump(pop());
							break;
						case JUMPI: {
							Word dest = pop();
							Word cond = pop();
							if (!cond.isZero()) {
								jump(dest);
							}
							break;
						}
						case PC:
							push(Word.of(pc - 1));
							break;
						case MSIZE:
							push(Word.of(memory.words * 32L));
							break;
						case GAS:
							push(Word.of(gas));
							break;
						case JUMPDEST:
							break;

						case PUSH1: {
							int n = 1;
							pushPush(n);
							break;
						}
						case PUSH32: {
							int n = 32;
							pushPush(n);
							break;
						}
						default:
							if (op >= PUSH1 && op <= PUSH32) {
								pushPush(op - PUSH1 + 1);
							} else if (op >= DUP1 && op <= DUP16) {
								push(peek(op - DUP1));
							} else if (op >= SWAP1 && op <= SWAP16) {
								swap(op - SWAP1 + 1);
							} else if (op >= LOG0 && op <= LOG4) {
								doLog(op - LOG0);
							} else {
								switch (op) {
									case CREATE:
									case CREATE2:
										doCreate(op);
										break;
									case CALL:
									case CALLCODE:
									case DELEGATECALL:
									case STATICCALL:
										doCall(op);
										break;
									case RETURN: {
										int offset = toInt(pop());
										int size = toInt(pop());
										returnData = Arrays.copyOfRange(ensureMemory(offset, size), offset, offset + size);
										halted = true;
										break;
									}
									case REVERT: {
										int offset = toInt(pop());
										int size = toInt(pop());
										returnData = Arrays.copyOfRange(ensureMemory(offset, size), offset, offset + size);
										return new EVMExecutionResult(false, gas, returnData, logs);
									}
									case INVALID:
										throw new HaltException();
									case SELFDESTRUCT:
										doSelfDestruct();
										halted = true;
										break;
									default:
										throw new HaltException();
								}
							}
					}
				}
				return new EVMExecutionResult(true, gas, returnData, logs);
			} catch (HaltException e) {
				return new EVMExecutionResult(false, 0, new byte[0], logs);
			}
		}

		private void pushPush(int n) throws HaltException {
			byte[] bytes = new byte[n];
			int available = code.length - pc;
			int copy = Math.min(n, available);
			System.arraycopy(code, pc, bytes, 0, copy);
			pc += n;
			push(Word.fromBytes(bytes));
		}

		private void doLog(int topicCount) throws HaltException {
			int offset = toInt(pop());
			int size = toInt(pop());
			List<Word> topics = new ArrayList<>();
			for (int i = 0; i < topicCount; i++) {
				topics.add(pop());
			}
			if (msg.isStatic()) {
				throw new HaltException();
			}
			charge(GLOG + (long) topicCount * GLOGTOPIC + (long) size * GLOGDATA);
			byte[] mem = ensureMemory(offset, size);
			logs.add(new EVMLog(msg.getAddress(), topics, Arrays.copyOfRange(mem, offset, offset + size)));
		}

		private void doCall(int opcode) throws HaltException {
			boolean isDelegate = opcode == DELEGATECALL;
			boolean isStaticCall = opcode == STATICCALL;
			Word gasWord = pop();
			Address to = Address.fromLast20Bytes(pop().toBytes());
			Word value = (opcode == CALL || opcode == CALLCODE) ? pop() : Word.ZERO;
			int inOffset = toInt(pop());
			int inSize = toInt(pop());
			int outOffset = toInt(pop());
			int outSize = toInt(pop());

			charge(GCALL);
			if (!isDelegate && !value.isZero()) {
				if (msg.isStatic()
						|| ws.getBalance(msg.getAddress()).compareTo(value.toBigInteger()) < 0) {
					push(Word.ZERO);
					return;
				}
			}

			long forward = Math.min(toLong(gasWord), gas);
			charge(forward);

			boolean newAccount = false;
			if (!isDelegate && !value.isZero()) {
				charge(GCALLVALUE);
				if (!ws.accountExists(to)) {
					charge(GNEWACCOUNT);
					newAccount = true;
				}
			}

			byte[] mem = ensureMemory(inOffset, inSize);
			ensureMemory(outOffset, outSize);
			byte[] inData = new byte[inSize];
			System.arraycopy(mem, inOffset, inData, 0, inSize);

			WorldState snapshot = ws.copy();
			int logSize = logs.size();
			if (!isDelegate && !value.isZero()) {
				ws.transfer(msg.getAddress(), to, value.toBigInteger());
				forward += GCALLSTIPEND;
			}

			Message child;
			if (opcode == CALL || opcode == STATICCALL) {
				child = Message.callOp(msg, to, value.toBigInteger(), inData, forward, isStaticCall);
			} else if (opcode == CALLCODE) {
				child = Message.callCodeOp(msg, to, value.toBigInteger(), inData, forward);
			} else {
				child = Message.delegateCallOp(msg, to, inData, forward);
			}

			EVMExecutionResult sub = depth >= MAX_CALL_DEPTH ? new EVMExecutionResult(false, 0, new byte[0], logs)
					: new Frame(child, ws, bc, logs, depth + 1).run();

			if (sub.isSuccess()) {
				push(Word.ONE);
			} else {
				ws.replaceFrom(snapshot);
				while (logs.size() > logSize) {
					logs.remove(logs.size() - 1);
				}
				push(Word.ZERO);
			}
			gas += sub.getGasRemaining();
			lastReturnData = sub.getReturnData();
			if (outSize > 0 && lastReturnData.length > 0) {
				byte[] mem2 = ensureMemory(outOffset, outSize);
				int copyLen = Math.min(outSize, lastReturnData.length);
				System.arraycopy(lastReturnData, 0, mem2, outOffset, copyLen);
			}
		}

		private void doCreate(int opcode) throws HaltException {
			Word value = pop();
			Word offsetW = pop();
			Word sizeW = pop();
			byte[] salt = opcode == CREATE2 ? pop().toBytes() : null;
			if (msg.isStatic()) {
				throw new HaltException();
			}
			int offset = toInt(offsetW);
			int size = toInt(sizeW);
			charge(GCREATE);
			byte[] mem = ensureMemory(offset, size);
			byte[] initCode = Arrays.copyOfRange(mem, offset, offset + size);

			Address from = msg.getAddress();
			EVMAccount creator = ws.getOrCreateAccount(from);
			long nonce = creator.incrementNonce();
			Address contract = opcode == CREATE2 ? create2Address(from, salt, Keccak.hash(initCode))
					: Rlp.createAddress(from, nonce);

			EVMAccount existing = ws.getAccount(contract);
			if (existing != null && (existing.hasCode() || existing.getNonce() != 0)) {
				push(Word.ZERO);
				return;
			}

			WorldState snapshot = ws.copy();
			int logSize = logs.size();
			if (!value.isZero()) {
				if (ws.getBalance(from).compareTo(value.toBigInteger()) < 0) {
					push(Word.ZERO);
					return;
				}
				ws.transfer(from, contract, value.toBigInteger());
			}
			ws.getOrCreateAccount(contract);

			long forward = gas;
			charge(forward);
			Message child = Message.createOp(msg, contract, initCode, forward);
			EVMExecutionResult sub = depth >= MAX_CALL_DEPTH ? new EVMExecutionResult(false, 0, new byte[0], logs)
					: new Frame(child, ws, bc, logs, depth + 1).run();

			if (sub.isSuccess()) {
				byte[] runtimeCode = sub.getReturnData();
				long deposit = (long) GCODEDEPOSIT * runtimeCode.length;
				if (runtimeCode.length > MAX_CODE_DEPOSIT || deposit > sub.getGasRemaining()) {
					ws.replaceFrom(snapshot);
					while (logs.size() > logSize) {
						logs.remove(logs.size() - 1);
					}
					push(Word.ZERO);
				} else {
					ws.setCode(contract, runtimeCode);
					gas += sub.getGasRemaining() - deposit;
					push(Word.fromBytes(contract.toBytes()));
				}
			} else {
				ws.replaceFrom(snapshot);
				while (logs.size() > logSize) {
					logs.remove(logs.size() - 1);
				}
				push(Word.ZERO);
				gas += sub.getGasRemaining();
			}
		}

		private void doSelfDestruct() throws HaltException {
			if (msg.isStatic()) {
				throw new HaltException();
			}
			Address beneficiary = Address.fromLast20Bytes(pop().toBytes());
			charge(GSELFDESTRUCT);
			BigInteger balance = ws.getBalance(msg.getAddress());
			if (balance.signum() > 0 && !ws.accountExists(beneficiary)) {
				charge(GNEWACCOUNT);
			}
			if (balance.signum() > 0) {
				ws.transfer(msg.getAddress(), beneficiary, balance);
			}
			ws.removeAccount(msg.getAddress());
		}

		// ---- stack helpers ----

		private void push(Word w) throws HaltException {
			if (stack.size() >= MAX_STACK) {
				throw new HaltException();
			}
			stack.add(w);
		}

		private Word pop() throws HaltException {
			if (stack.isEmpty()) {
				throw new HaltException();
			}
			return stack.remove(stack.size() - 1);
		}

		private Word peek(int fromTop) throws HaltException {
			int idx = stack.size() - 1 - fromTop;
			if (idx < 0) {
				throw new HaltException();
			}
			return stack.get(idx);
		}

		private void swap(int n) throws HaltException {
			int top = stack.size() - 1;
			int idx = top - n;
			if (idx < 0) {
				throw new HaltException();
			}
			Word tmp = stack.get(top);
			stack.set(top, stack.get(idx));
			stack.set(idx, tmp);
		}

		private void jump(Word dest) throws HaltException {
			int d = toInt(dest);
			if (d >= code.length || !validJumps[d]) {
				throw new HaltException();
			}
			pc = d;
		}

		// ---- gas / memory helpers ----

		private void charge(long cost) throws HaltException {
			if (cost > gas) {
				throw new HaltException();
			}
			gas -= cost;
		}

		private byte[] ensureMemory(int offset, int size) throws HaltException {
			if (size <= 0) {
				return memory.data;
			}
			long end = (long) offset + size;
			if (end > Integer.MAX_VALUE) {
				throw new HaltException();
			}
			int newWords = (int) ((end + 31) / 32);
			if (newWords > memory.words) {
				charge(memoryCost(newWords) - memoryCost(memory.words));
				memory.words = newWords;
			}
			if (end > memory.data.length) {
				memory.data = Arrays.copyOf(memory.data, (int) end);
			}
			return memory.data;
		}

		private void copyFrom(byte[] src, int memOffset, long srcOffset, int size) throws HaltException {
			if (size <= 0) {
				return;
			}
			byte[] mem = ensureMemory(memOffset, size);
			for (int i = 0; i < size; i++) {
				long si = srcOffset + i;
				mem[memOffset + i] = si < src.length ? src[(int) si] : 0;
			}
		}

		private int toInt(Word w) throws HaltException {
			if (w.toBigInteger().compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
				throw new HaltException();
			}
			return w.intValue();
		}

		private long toLong(Word w) {
			return w.toBigInteger().min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
		}

		private static long words(long size) {
			return (size + 31) / 32;
		}

		private static long memoryCost(int words) {
			return GMEMORY * words + (long) words * words / GQUADCOEFF;
		}

		private static Word signExtend(Word b, Word x) {
			if (b.toBigInteger().compareTo(BigInteger.valueOf(31)) >= 0) {
				return x;
			}
			int bitPos = (31 - b.intValue()) * 8;
			BigInteger v = x.toBigInteger();
			BigInteger above = Word.MAX_VALUE.shiftLeft(bitPos + 8);
			if (v.testBit(bitPos + 7)) {
				return Word.of(v.or(above));
			}
			return Word.of(v.andNot(above));
		}

		private static Address create2Address(Address creator, byte[] salt, byte[] initCodeHash) {
			byte[] out = new byte[1 + 20 + 32 + 32];
			out[0] = (byte) 0xff;
			System.arraycopy(creator.toBytes(), 0, out, 1, 20);
			System.arraycopy(salt, 0, out, 21, 32);
			System.arraycopy(initCodeHash, 0, out, 53, 32);
			return Address.fromLast20Bytes(Keccak.hash(out));
		}

		private static final class Memory {
			byte[] data = new byte[0];
			int words = 0;
		}
	}

	private static final class HaltException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
