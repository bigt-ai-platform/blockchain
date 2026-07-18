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
		assertEquals("80d707086380536fbe9b0894445a2182f6b0a584765ff4ef96fede5add9a1bdd", block.getHashAsString());
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

		String t = "01000000615d21aacd5c6b11571f3a69c9ed408690ea05f063e8ad31a945ecda22261601615d21aacd5c6b11571f3a69c9ed408690ea05f063e8ad31a945ecda22261601fe8dc42e887a46b4e27969a74e256eadfc34678e03b7aa41da2c9bce36f9e01469d48f6800000000ae470120000000000200000000000000052c62022bdf6a05a961cf27a47355486891ebb9ee6892f8010000000600000000000000010100000001bb0977b65088b48bd069b86f55e652cf68240f1ddb744d0199ddc7ef09db8c0035309ef47df86bf23613939e14169e8df8605cde1b92c8916849837e696516ad0100000049483045022100fa7d6a086c244d84f942049c8e24d6f9f854ab85abce4eeaa77be984040e00d302204f5aa11ea921d718f133679b558c016763f0c9995156baed5dcd8033a1b1838e01ffffffff0100000008016345785d6b73b001bc232102721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975ac02030f424001bc1976a91451d65cb4f2e64551c447cd41635dd9214bbaf19d88ac08016345785d5c2d8801bc232102721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975ac00000000000000000000000000000000420000007b0a2020226b7622203a205b207b0a20202020226b657922203a20226d656d6f222c0a202020202276616c756522203a20227061794c697374220a20207d205d0a7d00000000";

		Block tb = PARAMS.getDefaultSerializer().makeBlock(Utils.HEX.decode(t));
		System.out.printf(tb.toString());

		assertEquals(t, Utils.HEX.encode(tb.bitcoinSerialize()));
		
		// Assert transaction details
		assertEquals(1, tb.getTransactions().size());
		
		Transaction tx = tb.getTransactions().get(0);
		
		// Assert transaction is not coinbase
		assertFalse(tx.isCoinBase());
		
		// Assert input details
		assertEquals(1, tx.getInputs().size());
		TransactionInput input = tx.getInputs().get(0);
		assertEquals("008cdb09efc7dd99014d74db1d0f2468cf52e6556fb869d08bb48850b67709bb", 
		             Utils.HEX.encode(input.getOutpoint().getBlockHash().getBytes()));
		assertEquals("ad1665697e83496891c8921bde5c60f88d9e16149e931336f26bf87df49e3035", 
		             Utils.HEX.encode(input.getOutpoint().getTxHash().getBytes()));
		assertEquals(1, input.getOutpoint().getIndex());
		
		// Assert output details
		assertEquals(2, tx.getOutputs().size());
		TransactionOutput output1 = tx.getOutputs().get(0);
		TransactionOutput output2 = tx.getOutputs().get(1);
		
		assertEquals(Coin.valueOf(1000000L), output1.getValue());
		assertEquals(Coin.valueOf(99999999996997000L), output2.getValue());
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

	// @Test
	public void testJSON() throws Exception {
		Block block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);
		Block header = block.cloneAsHeader();
		Block reparsed = PARAMS.getDefaultSerializer().makeBlock(header.bitcoinSerialize());
		assertEquals(reparsed, header);
	}

}
