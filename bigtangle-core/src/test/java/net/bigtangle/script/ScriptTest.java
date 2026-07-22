/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.script;

import static net.bigtangle.core.Utils.HEX;
import static net.bigtangle.script.ScriptOpCodes.OP_0;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.hamcrest.MatcherAssert.assertThat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.exception.ScriptException;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script.VerifyFlag;

public class ScriptTest {
	// From tx 05e04c26c12fe408a3c1b71aa7996403f6acad1045252b1c62e055496f4d2cb1 on
	// the testnet.

	static final String sigProg = "47304402202b4da291cc39faf8433911988f9f49fc5c995812ca2f94db61468839c228c3e90220628bff3ff32ec95825092fa051cba28558a981fcf59ce184b14f2e215e69106701410414b38f4be3bb9fa0f4f32b74af07152b2f2f630bc02122a491137b6c523e46f18a0d5034418966f93dfc37cc3739ef7b2007213a302b7fba161557f4ad644a1c";

	static final String pubkeyProg = "76a91433e81a941e64cda12c6a299ed322ddbdd03f8d0e88ac";

	private static final NetworkParameters PARAMS = MainNetParams.get();

	private static final Logger log = LoggerFactory.getLogger(ScriptTest.class);

	@BeforeEach
	public void setUp() throws Exception {

	}

	@Test
	public void testScriptSig() throws Exception {
		byte[] sigProgBytes = HEX.decode(sigProg);
		Script script = new Script(sigProgBytes);
		// Test we can extract the from address.
		byte[] hash160 = Utils.sha256hash160(script.getPubKey());
		Address a =   Address.fromHash160(PARAMS, hash160);
		assertEquals("15jTWe6r9zqxkjjLFntAWADZosAwiuw4U5", a.toString());
	}

    @Test
    public void testNumberBuilder16() {
        ScriptBuilder builder = new ScriptBuilder();
        // Numbers greater than 16 must be encoded with PUSHDATA
        builder.number(15).number(16).number(17);
        builder.number(0, 17).number(1, 16).number(2, 15);
        Script script = builder.build();
        assertEquals("PUSHDATA(1)[11] 16 15 15 16 PUSHDATA(1)[11]", script.toString());
    }

    @Test
    public void testScriptExceptionNonTrueStack() {
        NetworkParameters params = MainNetParams.get();
        Transaction tx = new Transaction(params);
        tx.addInput(TransactionInput.fromScriptBytes(params, tx, new byte[0]));

        // Create a script that will result in a non-true stack
        Script scriptSig = new Script(new byte[] {OP_0}); // Pushes false
        Script scriptPubKey = new Script(new byte[] {OP_0}); // Pushes false

        Exception exception = assertThrows(ScriptException.class, () -> {
            scriptSig.correctlySpends(tx, 0, scriptPubKey, EnumSet.noneOf(VerifyFlag.class));
        });
        assertTrue(exception.getMessage().startsWith("Script resulted in a non-true stack"));
    }

	@Test
	public void testAddress() {
		log.debug(  Address.fromHash160(MainNetParams.get(), Utils.HEX.decode("bcdb06ac26dcdadb3b17859d14cf45ca285be9b9"))
				.toString());
	}

	@Test
	public void testMultiSig() throws Exception {
		List<PQKey> keys = Lists.newArrayList(PQKey.createNew(), PQKey.createNew());
		assertTrue(ScriptBuilder.createMultiSigOutputScript(2, keys).isSentToMultiSig());
		Script script = ScriptBuilder.createMultiSigOutputScript(3, keys);
		assertTrue(script.isSentToMultiSig());
		List<PQKey> pubkeys = new ArrayList<PQKey>(3);
		for (PQKey key : keys)
			pubkeys.add(PQKey.fromPublicOnly(key.getPubKey()));
		assertEquals(script.getPubKeys(), pubkeys);
		assertFalse(ScriptBuilder.createOutputScript(PQKey.createNew()).isSentToMultiSig());
		try {
			// Fail if we ask for more signatures than keys.
			Script.createMultiSigOutputScript(4, keys);
			fail();
		} catch (Throwable e) {
			// Expected.
		}
		try {
			// Must have at least one signature required.
			Script.createMultiSigOutputScript(0, keys);
		} catch (Throwable e) {
			// Expected.
		}
		// Actual execution is tested by the data driven tests.
	}

	@Test
	public void testP2SHOutputScript() throws Exception {
		Address p2shAddress = Address.fromBase58Version(MainNetParams.get(),MainNetParams.get().getP2SHHeader(), "35b9vsyH1KoFT5a5KtrKusaCcPLkiSo1tU");
		assertTrue(ScriptBuilder.createOutputScript(p2shAddress).isPayToScriptHash());
	}

	@Test
	public void testIp() throws Exception {
		byte[] bytes = HEX.decode(
				"41043e96222332ea7848323c08116dddafbfa917b8e37f0bdf63841628267148588a09a43540942d58d49717ad3fabfe14978cf4f0a8b84d2435dad16e9aa4d7f935ac");
		Script s = new Script(bytes);
		assertTrue(s.isSentToRawPubKey());
	}

	@Test
	public void createAndUpdateEmptyInputScript() throws Exception {
		TransactionSignature dummySig = TransactionSignature.dummy();
		PQKey key = PQKey.createNew();

		// pay-to-pubkey
		Script inputScript = ScriptBuilder.createInputScript(dummySig);
		assertThat(inputScript.getChunks().get(0).data, equalTo(dummySig.encodeToBitcoin()));
		inputScript = ScriptBuilder.createInputScript(null);
		assertThat(inputScript.getChunks().get(0).opcode, equalTo(OP_0));

		// pay-to-address
		inputScript = ScriptBuilder.createInputScript(dummySig, key);
		assertThat(inputScript.getChunks().get(0).data, equalTo(dummySig.encodeToBitcoin()));
		inputScript = ScriptBuilder.createInputScript(null, key);
		assertThat(inputScript.getChunks().get(0).opcode, equalTo(OP_0));
		assertThat(inputScript.getChunks().get(1).data, equalTo(key.getPubKey()));

		// pay-to-script-hash
		PQKey key2 = PQKey.createNew();
		Script multisigScript = ScriptBuilder.createMultiSigOutputScript(2, Arrays.asList(key, key2));
		inputScript = ScriptBuilder.createP2SHMultiSigInputScript(Arrays.asList(dummySig, dummySig), multisigScript);
		assertThat(inputScript.getChunks().get(0).opcode, equalTo(OP_0));
		assertThat(inputScript.getChunks().get(1).data, equalTo(dummySig.encodeToBitcoin()));
		assertThat(inputScript.getChunks().get(2).data, equalTo(dummySig.encodeToBitcoin()));
		assertThat(inputScript.getChunks().get(3).data, equalTo(multisigScript.getProgram()));

		inputScript = ScriptBuilder.createP2SHMultiSigInputScript(null, multisigScript);
		assertThat(inputScript.getChunks().get(0).opcode, equalTo(OP_0));
		assertThat(inputScript.getChunks().get(1).opcode, equalTo(OP_0));
		assertThat(inputScript.getChunks().get(2).opcode, equalTo(OP_0));
		assertThat(inputScript.getChunks().get(3).data, equalTo(multisigScript.getProgram()));

		inputScript = ScriptBuilder.updateScriptWithSignature(inputScript, dummySig.encodeToBitcoin(), 0, 1, 1);
		assertThat(inputScript.getChunks().get(0).opcode, equalTo(OP_0));
		assertThat(inputScript.getChunks().get(1).data, equalTo(dummySig.encodeToBitcoin()));
		assertThat(inputScript.getChunks().get(2).opcode, equalTo(OP_0));
		assertThat(inputScript.getChunks().get(3).data, equalTo(multisigScript.getProgram()));

		inputScript = ScriptBuilder.updateScriptWithSignature(inputScript, dummySig.encodeToBitcoin(), 1, 1, 1);
		assertThat(inputScript.getChunks().get(0).opcode, equalTo(OP_0));
		assertThat(inputScript.getChunks().get(1).data, equalTo(dummySig.encodeToBitcoin()));
		assertThat(inputScript.getChunks().get(2).data, equalTo(dummySig.encodeToBitcoin()));
		assertThat(inputScript.getChunks().get(3).data, equalTo(multisigScript.getProgram()));

		// updating scriptSig with no missing signatures
		try {
			ScriptBuilder.updateScriptWithSignature(inputScript, dummySig.encodeToBitcoin(), 1, 1, 1);
			fail("Exception expected");
		} catch (Exception e) {
			assertEquals(IllegalArgumentException.class, e.getClass());
		}
	}

	@Test
	public void testOp0() {
		// Check that OP_0 doesn't NPE and pushes an empty stack frame.
		Transaction tx = new Transaction(PARAMS);
		;
		tx.addInput(  TransactionInput.fromScriptBytes(PARAMS, tx, new byte[] {}));
		Script script = new ScriptBuilder().smallNum(0).build();

		LinkedList<byte[]> stack = new LinkedList<byte[]>();
		Script.executeScript(tx, 0, script, stack, Script.ALL_VERIFY_FLAGS);
		assertEquals(0, stack.get(0).length, "OP_0 push length");
	}

	@Test
	public void testCLTVPaymentChannelOutput() {
		Script script = ScriptBuilder.createCLTVPaymentChannelOutput(BigInteger.valueOf(20), PQKey.createNew());
		assertTrue(script.isSentToCLTVPaymentChannel(), "script is locktime-verify");
	}

	@Test
	public void getToAddress() throws Exception {
		// pay to pubkey
		PQKey toKey = PQKey.createNew();
		Address toAddress = Address.fromHash160(PARAMS, Utils.sha256hash160(toKey.getPubKey()));
		assertEquals(toAddress, ScriptBuilder.createOutputScript(toKey).getToAddress(PARAMS, true));
		// pay to pubkey hash
		assertEquals(toAddress, ScriptBuilder.createOutputScript(toAddress).getToAddress(PARAMS, true));
		// pay to script hash
		Script p2shScript = ScriptBuilder.createP2SHOutputScript(new byte[20]);
		Address scriptAddress = Address.fromP2SHScript(PARAMS, p2shScript);
		assertEquals(scriptAddress, p2shScript.getToAddress(PARAMS, true));
	}

	@Test
	public void getToAddressNoPubKey() throws Exception {
		assertThrows(ScriptException.class, () -> {
			ScriptBuilder.createOutputScript(PQKey.createNew()).getToAddress(PARAMS, false);
		});

	}

	/** Test encoding of zero, which should result in an opcode */
	@Test
	public void numberBuilderZero() {
		final ScriptBuilder builder = new ScriptBuilder();

		// 0 should encode directly to 0
		builder.number(0);
		assertArrayEquals(new byte[] { 0x00 // Pushed data
		}, builder.build().getProgram());
	}

	@Test
	public void numberBuilderPositiveOpCode() {
		final ScriptBuilder builder = new ScriptBuilder();

		builder.number(5);
		assertArrayEquals(new byte[] { 0x55 // Pushed data
		}, builder.build().getProgram());
	}

	@Test
	public void numberBuilderBigNum() {
		ScriptBuilder builder = new ScriptBuilder();
		// 21066 should take up three bytes including the length byte
		// at the start

		builder.number(0x524a);
		assertArrayEquals(new byte[] { 0x02, // Length of the pushed data
				0x4a, 0x52 // Pushed data
		}, builder.build().getProgram());

		// Test the trimming code ignores zeroes in the middle
		builder = new ScriptBuilder();
		builder.number(0x110011);
		assertEquals(4, builder.build().getProgram().length);

		// Check encoding of a value where signed/unsigned encoding differs
		// because the most significant byte is 0x80, and therefore a
		// sign byte has to be added to the end for the signed encoding.
		builder = new ScriptBuilder();
		builder.number(0x8000);
		assertArrayEquals(new byte[] { 0x03, // Length of the pushed data
				0x00, (byte) 0x80, 0x00 // Pushed data
		}, builder.build().getProgram());
	}

	@Test
	public void numberBuilderNegative() {
		// Check encoding of a negative value
		final ScriptBuilder builder = new ScriptBuilder();
		builder.number(-5);
		assertArrayEquals(new byte[] { 0x01, // Length of the pushed data
				((byte) 133) // Pushed data
		}, builder.build().getProgram());
	}

	@Test
	public void numberBuilder16() {
		ScriptBuilder builder = new ScriptBuilder();
		// Numbers greater than 16 must be encoded with PUSHDATA
		builder.number(15).number(16).number(17);
		builder.number(0, 17).number(1, 16).number(2, 15);
		Script script = builder.build();
		assertEquals("PUSHDATA(1)[11] 16 15 15 16 PUSHDATA(1)[11]", script.toString());
	}
}
