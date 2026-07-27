/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.Coin;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.exception.ScriptException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.script.ScriptOpCodes;

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
	private static final Address ADDRESS = Address.fromHash160(PARAMS, Utils.sha256hash160(PQKey.createNew().getPubKey()));

	private Transaction tx;

	@BeforeEach
	public void setUp() throws Exception {
		tx = FakeTxBuilder.createFakeTx(PARAMS);
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
		tx.addOutput(  TransactionOutput.fromAddress(PARAMS, null, Coin.COIN, ADDRESS));
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

			assertEquals(newOut.getMemo() != null && newOut.getMemo().length() > 0, true);
		}

	}

	@Test
	public void testAddSignedInputThrowsExceptionWhenScriptIsNotToRawPubKeyAndIsNotToAddress() {
		assertThrows(ClassCastException.class, () -> {
			PQKey key = PQKey.createNew();
			Address addr = Address.fromHash160(PARAMS, Utils.sha256hash160(key.getPubKey()));
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

	@Test
	public void testCalculateSignatureWithPQKey() throws Exception {
		// Create a PQ key pair
		PQKey key = PQKey.createNew();
		// Create a minimal transaction
		Transaction tx = new Transaction(PARAMS);
		tx.setVersion(PQConstants.TX_PQ_VERSION);
		// Add an input and output
		tx.addInput(Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH, 0, new ScriptBuilder().data(new byte[]{0x05, 0x01}).op(ScriptOpCodes.OP_CHECKSIG).build());
		tx.addOutput(Coin.COIN, key);

		// Calculate signature - this should store PQ bundle on tx
		Script outputScript = ScriptBuilder.createOutputScript(key);
		TransactionSignature sig = tx.calculateSignature(0, key, outputScript, Transaction.SigHash.ALL, false);

		// Verify pqSignatureBundle was stored
		byte[] storedBundle = tx.getPqSignatureBundle();
		assertNotNull(storedBundle, "calculateSignature must store PQ signature bundle");

		// Verify it's a valid SignatureBundle
		SignatureBundle bundle = SignatureBundle.deserialize(storedBundle);
		assertNotNull(bundle);
		assertTrue(bundle.entries().size() > 0);

		// Create input script from the stored bundle and verify it spends the output
		// Include pubkey for P2PKH verification
		Script inputScript = ScriptBuilder.createInputScriptForPQ(bundle, key);
		// This should not throw
		inputScript.correctlySpends(tx, 0, outputScript, Script.ALL_VERIFY_FLAGS);
	}

}
