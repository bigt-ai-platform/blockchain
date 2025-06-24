/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.exception.ScriptException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;

/**
 * Just check the Transaction.verify() method. Most methods that have
 * complicated logic in Transaction are tested elsewhere, e.g. signing and
 * hashing are well exercised by the wallet tests, the full block chain tests
 * and so on. The verify method is also exercised by the full block chain tests,
 * but it can also be used by API users alone, so we make sure to cover it here
 * as well.
 */
public class TransactionTest {
	private static final NetworkParameters PARAMS = MainNetParams.get();
	private static final Address ADDRESS = new ECKey().toAddress(PARAMS);

	private Transaction tx;

	@BeforeEach
	public void setUp() throws Exception {
		tx = FakeTxBuilder.createFakeTx(PARAMS);
	}

	@Test
	public void duplicateOutPoint() throws Exception {
		assertThrows(VerificationException.DuplicatedOutPoint.class, () -> {
			TransactionInput input = tx.getInput(0);
			input.setScriptBytes(new byte[1]);
			tx.addInput(input.duplicateDetached());
			tx.verify();
		});

	}

	@Test
	public void coinbaseInputInNonCoinbaseTX() throws Exception {
		assertThrows(VerificationException.UnexpectedCoinbaseInput.class, () -> {
			tx.addInput(Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH, 0xFFFFFFFFL,
					new ScriptBuilder().data(new byte[10]).build());
			tx.verify();
		});
	}

	@Test
	public void coinbaseScriptSigTooSmall() throws Exception {
		assertThrows(VerificationException.CoinbaseScriptSizeOutOfRange.class, () -> {
			tx.clearInputs();
			tx.addInput(Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH, 0xFFFFFFFFL, new ScriptBuilder().build());
			tx.verify();
		});
	}

	@Test
	public void coinbaseScriptSigTooLarge() throws Exception {
		assertThrows(VerificationException.CoinbaseScriptSizeOutOfRange.class, () -> {
			tx.clearInputs();
			TransactionInput input = tx.addInput(Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH, 0xFFFFFFFFL,
					new ScriptBuilder().data(new byte[99]).build());
			assertEquals(101, input.getScriptBytes().length);
			tx.verify();
		});
	}

	@Test
	public void testOptimalEncodingMessageSize() {
		Transaction tx = new Transaction(PARAMS);

		int length = tx.length;

		// add basic transaction input, check the length
		tx.addOutput(new TransactionOutput(PARAMS, null, Coin.COIN, ADDRESS));
		length += getCombinedLength(tx.getOutputs());

		// add basic output, check the length
		length += getCombinedLength(tx.getInputs());

		// optimal encoding size should equal the length we just calculated
		assertEquals(tx.getOptimalEncodingMessageSize(), length);
	}

	private int getCombinedLength(List<? extends Message> list) {
		int sumOfAllMsgSizes = 0;
		for (Message m : list) {
			sumOfAllMsgSizes += m.getMessageSize() + 1;
		}
		return sumOfAllMsgSizes;
	}

	@Test
	public void testCLTVPaymentChannelTransactionSpending() {
		BigInteger time = BigInteger.valueOf(20);

		ECKey from = new ECKey(), to = new ECKey(), incorrect = new ECKey();
		Script outputScript = ScriptBuilder.createCLTVPaymentChannelOutput(time, from, to);

		Transaction tx = new Transaction(PARAMS);
		tx.addInput(new TransactionInput(PARAMS, tx, new byte[] {}));
		tx.getInput(0).setSequenceNumber(0);
		tx.setLockTime(time.subtract(BigInteger.ONE).longValue());
		TransactionSignature fromSig = tx.calculateSignature(0, from, outputScript, Transaction.SigHash.SINGLE, false);
		TransactionSignature toSig = tx.calculateSignature(0, to, outputScript, Transaction.SigHash.SINGLE, false);
		TransactionSignature incorrectSig = tx.calculateSignature(0, incorrect, outputScript,
				Transaction.SigHash.SINGLE, false);
		Script scriptSig = ScriptBuilder.createCLTVPaymentChannelInput(fromSig, toSig);
		Script refundSig = ScriptBuilder.createCLTVPaymentChannelRefund(fromSig);
		Script invalidScriptSig1 = ScriptBuilder.createCLTVPaymentChannelInput(fromSig, incorrectSig);
		Script invalidScriptSig2 = ScriptBuilder.createCLTVPaymentChannelInput(incorrectSig, toSig);

		try {
			scriptSig.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
		} catch (ScriptException e) {
			e.printStackTrace();
			fail("Settle transaction failed to correctly spend the payment channel");
		}

		try {
			refundSig.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
			fail("Refund passed before expiry");
		} catch (ScriptException e) {
		}
		try {
			invalidScriptSig1.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
			fail("Invalid sig 1 passed");
		} catch (ScriptException e) {
		}
		try {
			invalidScriptSig2.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
			fail("Invalid sig 2 passed");
		} catch (ScriptException e) {
		}
	}

	@Test
	public void testCLTVPaymentChannelTransactionRefund() {
		BigInteger time = BigInteger.valueOf(20);

		ECKey from = new ECKey(), to = new ECKey(), incorrect = new ECKey();
		Script outputScript = ScriptBuilder.createCLTVPaymentChannelOutput(time, from, to);

		Transaction tx = new Transaction(PARAMS);
		tx.addInput(new TransactionInput(PARAMS, tx, new byte[] {}));
		tx.getInput(0).setSequenceNumber(0);
		tx.setLockTime(time.add(BigInteger.ONE).longValue());
		TransactionSignature fromSig = tx.calculateSignature(0, from, outputScript, Transaction.SigHash.SINGLE, false);
		TransactionSignature incorrectSig = tx.calculateSignature(0, incorrect, outputScript,
				Transaction.SigHash.SINGLE, false);
		Script scriptSig = ScriptBuilder.createCLTVPaymentChannelRefund(fromSig);
		Script invalidScriptSig = ScriptBuilder.createCLTVPaymentChannelRefund(incorrectSig);

		try {
			scriptSig.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
		} catch (ScriptException e) {
			e.printStackTrace();
			fail("Refund failed to correctly spend the payment channel");
		}

		try {
			invalidScriptSig.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
			fail("Invalid sig passed");
		} catch (ScriptException e) {
		}
	}

	@Test
	public void testToStringWhenIteratingOverAnInputCatchesAnException() {
		Transaction tx = FakeTxBuilder.createFakeTx(PARAMS);
		TransactionInput ti = new TransactionInput(PARAMS, tx, new byte[0]) {
			@Override
			public Script getScriptSig() throws ScriptException {
				throw new ScriptException("");
			}
		};

		tx.addInput(ti);
		assertEquals(tx.toString().contains("[exception: "), true);
	}

	@Test
	public void testMemoUTXO() {

		tx.setMemo(new MemoInfo("Test:" + tx));
		boolean isCoinBase = tx.isCoinBase();
		for (TransactionOutput out : tx.getOutputs()) {
			Script script = new Script(new byte[0]);
			String fromAddress = "";
			try {
				if (!isCoinBase) {
					fromAddress = tx.getInputs().get(0).getFromAddress().toBase58();
				}
			} catch (ScriptException e) {
				// No address found.
			}
			int minsignnumber = 1;
			if (script.isSentToMultiSig()) {
				minsignnumber = script.getNumberOfSignaturesRequiredToSpend();
			}
			UTXO newOut = new UTXO(tx.getHash(), out.getIndex(), out.getValue(), isCoinBase, script, "", null,
					fromAddress, tx.getMemo(), Utils.HEX.encode(out.getValue().getTokenid()), false, false, false,
					minsignnumber, 0, System.currentTimeMillis() / 1000, null);

			assertEquals(newOut.getMemo().contains("Test"), true);
		}

	}

	@Test
	public void testAddSignedInputThrowsExceptionWhenScriptIsNotToRawPubKeyAndIsNotToAddress() {
		assertThrows(ScriptException.class, () -> {
			ECKey key = new ECKey();
			Address addr = key.toAddress(PARAMS);
			Transaction fakeTx = FakeTxBuilder.createFakeTx(PARAMS, Coin.COIN, addr);

			Transaction tx = new Transaction(PARAMS);
			tx.addOutput(fakeTx.getOutput(0));

			Script script = ScriptBuilder.createOpReturnScript(new byte[0]);

			tx.addSignedInput(fakeTx.getOutput(0).getOutPointFor(Sha256Hash.ZERO_HASH), script, key);
		});

	}

	@Test
	public void optInFullRBF() {
		// a standard transaction as wallets would create
		Transaction tx = FakeTxBuilder.createFakeTx(PARAMS);
		assertFalse(tx.isOptInFullRBF());

		tx.getInputs().get(0).setSequenceNumber(TransactionInput.NO_SEQUENCE - 2);
		assertTrue(tx.isOptInFullRBF());
	}

	/**
	 * Ensure that hashForSignature() doesn't modify a transaction's data, which
	 * could wreak multithreading havoc.
	 */
	// @Test
//    public void testHashForSignatureThreadSafety() {
//        Block genesis = MainNetParams.get().getGenesisBlock();
//        Block block1 = BlockForTest.createNextBlock(genesis,new ECKey().toAddress(MainNetParams.get()),
//                genesis.getTransactions().get(0).getOutput(0).getOutPointFor(), genesis.getHash());
//
//        final Transaction tx = block1.getTransactions().get(1);
//        final String txHash = tx.getHashAsString();
//        final String txNormalizedHash = tx.hashForSignature(0, new byte[0], Transaction.SigHash.ALL.byteValue())
//                .toString();
//
//        for (int i = 0; i < 100; i++) {
//            // ensure the transaction object itself was not modified; if it was,
//            // the hash will change
//            assertEquals(txHash, tx.getHashAsString());
//            new Thread() {
//                public void run() {
//                    assertEquals(txNormalizedHash,
//                            tx.hashForSignature(0, new byte[0], Transaction.SigHash.ALL.byteValue()).toString());
//                }
//            };
//        }
//    }
}
