/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.ScriptOpCodes;
 

public class BlockTest {
	private static final NetworkParameters PARAMS = MainNetParams.get();

	public static final byte[] blockBytes;

	static {
		// Block
		// 00000000a6e5eb79dcec11897af55e90cd571a4335383a3ccfbc12ec81085935
		// One with lots of transactions in, so a good test of the merkle tree
		// hashing.
		Block gen= UtilGeneseBlock.createGenesis(PARAMS);
		System.out.println("Genesis: " + gen. toString());
		blockBytes =gen.unsafeBitcoinSerialize();

	}

	 
	  @Test
	public void testSerial() throws Exception {
		Block block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);
	 System.out.println("Genesis: " + block. toString());
		assertEquals("e842dd0c075b78f9863fcb51790ec8c5e84a05717974b299d40ea39fea8bc6ad", block.getHashAsString());
	}

	// TODO NO BINARY @Test
	public void testBadTransactions() throws Exception {
		Block block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);
		// Re-arrange so the coinbase transaction is not first.
		Transaction tx1 = block.transactions.get(0);
		Transaction tx2 = block.transactions.get(1);
		block.transactions.set(0, tx2);
		block.transactions.set(1, tx1);
		try {
			block.verify();
			fail();
		} catch (VerificationException e) {
			// We should get here.
		}
	}

	@Test
	public void testHeaderParse() throws Exception {
		Block block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);
		Block header = block.cloneAsHeader();
		Block reparsed = PARAMS.getDefaultSerializer().makeBlock(header.bitcoinSerialize());
		assertEquals(reparsed, header);
	}

	@Test
	public void testSerial2() throws Exception {
		Block b = UtilGeneseBlock.createGenesis(PARAMS);
		byte[] serialized = b.unsafeBitcoinSerialize();
		Block reparsed = PARAMS.getDefaultSerializer().makeBlock(serialized);
		assertEquals(b.getHashAsString(), reparsed.getHashAsString());
	}

	@Test
	public void testBitcoinSerialization() throws Exception {
		// We have to be able to reserialize everything exactly as we found it
		// for hashing to work. This test also
		// proves that transaction serialization works, along with all its
		// subobjects like scripts and in/outpoints.
		//
		// NB: This tests the bitcoin serialization protocol.
		Block block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);

		assertTrue(Arrays.equals(blockBytes, block.bitcoinSerialize()));
	}

	@Test
	@Disabled("This test is broken, see")
	public void testUpdateLength() {
		NetworkParameters params = MainNetParams.get();
		Block block = UtilsTest.createBlock(PARAMS, UtilGeneseBlock.createGenesis(params),
				UtilGeneseBlock.createGenesis(params));
		// assertEquals(block.bitcoinSerialize().length, block.length);
		final int origBlockLen = block.length;
		Transaction tx = new Transaction(params);
		// this is broken until the transaction has > 1 input + output (which is
		// required anyway...)
		// assertTrue(tx.length == tx.bitcoinSerialize().length && tx.length ==
		// 8);
		byte[] outputScript = new byte[10];
		Arrays.fill(outputScript, (byte) ScriptOpCodes.OP_FALSE);
		tx.addOutput(new TransactionOutput(params, null, Coin.COIN, outputScript));
		tx.addInput(TransactionInput.fromOutpoint4(params, null, new byte[] { (byte) ScriptOpCodes.OP_FALSE },
				TransactionOutPoint.fromTransactionOutPoint4(params, 0, Sha256Hash.of(new byte[] { 1 }),
						Sha256Hash.of(new byte[] { 1 }))));
		// int origTxLength = NetworkParameters.HEADER_SIZE; // TODO new length
		// assertEquals(tx.unsafeBitcoinSerialize().length, tx.length);
		// assertEquals(origTxLength, tx.length);
		block.addTransaction(tx);
		assertEquals(block.unsafeBitcoinSerialize().length, block.length);
		assertEquals(origBlockLen + tx.length, block.length);
		block.getTransactions().get(1).getInputs().get(0)
				.setScriptBytes(new byte[] { (byte) ScriptOpCodes.OP_FALSE, (byte) ScriptOpCodes.OP_FALSE });
		assertEquals(block.length, origBlockLen + tx.length);
		// assertEquals(tx.length, origTxLength + 1);
		block.getTransactions().get(1).getInputs().get(0).clearScriptBytes();
		assertEquals(block.length, block.unsafeBitcoinSerialize().length);
		assertEquals(block.length, origBlockLen + tx.length);
		// assertEquals(tx.length, origTxLength - 1);
		block.getTransactions().get(1)
				.addInput(TransactionInput.fromOutpoint4(params, null, new byte[] { (byte) ScriptOpCodes.OP_FALSE },
						TransactionOutPoint.fromTransactionOutPoint4(params, 0, Sha256Hash.of(new byte[] { 1 }),
								Sha256Hash.of(new byte[] { 1 }))));
		assertEquals(block.length, origBlockLen + tx.length);
		// assertEquals(tx.length, origTxLength + 41); // - 1 + 40 + 1 + 1
	}

	public static ObjectMapper jsonmapper() {

		// getJsonName(response);
		ObjectMapper mapper = new ObjectMapper();
		// mapper.registerModule(new JavaTimeModule());
		// mapper.findAndRegisterModules();
		mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
		// mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
		// true);
		// SimpleDateFormat outputFormat = new SimpleDateFormat("dd MMM yyyy");
		// mapper.setDateFormat(outputFormat);

		mapper.setSerializationInclusion(Include.NON_EMPTY);
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		return mapper;
		// mapper.writeValue(System.out, response);
	}

}
