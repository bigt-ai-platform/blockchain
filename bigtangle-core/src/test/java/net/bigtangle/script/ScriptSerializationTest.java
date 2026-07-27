/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.script;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;

public class ScriptSerializationTest {
    private static final NetworkParameters PARAMS = MainNetParams.get();
    private PQKey key1;
    private PQKey key2;
    private PQKey key3;

    @BeforeEach
    public void setUp() throws Exception {
        key1 = PQKey.createNew();
        key2 = PQKey.createNew();
        key3 = PQKey.createNew();
    }

    @Test
    public void testScriptSerializationWithPayToAddress() throws IOException {
        // Create a pay-to-address script
        byte[] pubkeyHash = Utils.sha256hash160(key1.getPubKey());
        Script script = ScriptBuilder.createOutputScript(  Address.fromHash160(PARAMS, pubkeyHash));
        
        // Serialize the script
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        
        // Deserialize the script
        Script deserializedScript = new Script(serialized);
        
        // Verify the deserialized script is equivalent
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
        assertTrue(deserializedScript.isSentToAddress());
        assertArrayEquals(pubkeyHash, deserializedScript.getPubKeyHash());
    }

    @Test
    public void testScriptSerializationWithPayToPubKeyHash() throws IOException {
        // Create a P2PKH script from a key
        Script script = ScriptBuilder.createOutputScript(key1);
        
        // Serialize the script
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        
        // Deserialize the script
        Script deserializedScript = new Script(serialized);
        
        // Verify the deserialized script is equivalent
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
        assertTrue(deserializedScript.isSentToAddress());
        assertArrayEquals(key1.getPubKeyHash(), deserializedScript.getPubKeyHash());
    }

    @Test
    public void testScriptSerializationWithMultiSig() throws IOException {
        // Create a multi-sig script
        List<PQKey> keys = Arrays.asList(key1, key2, key3);
        Script script = ScriptBuilder.createMultiSigOutputScript(2, keys);
        
        // Serialize the script
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        
        // Deserialize the script
        Script deserializedScript = new Script(serialized);
        
        // Verify the deserialized script is equivalent
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
        assertTrue(deserializedScript.isSentToMultiSig());
        assertEquals(2, deserializedScript.getNumberOfSignaturesRequiredToSpend());
        assertEquals(keys.size(), deserializedScript.getPubKeys().size());
    }

    @Test
    public void testScriptSerializationWithP2SH() throws IOException {
        // Create a P2SH script
        byte[] scriptHash = new byte[20];
        Arrays.fill(scriptHash, (byte) 0x01);
        Script script = ScriptBuilder.createP2SHOutputScript(scriptHash);
        
        // Serialize the script
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        
        // Deserialize the script
        Script deserializedScript = new Script(serialized);
        
        // Verify the deserialized script is equivalent
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
        assertTrue(deserializedScript.isPayToScriptHash());
        assertArrayEquals(scriptHash, deserializedScript.getPubKeyHash());
    }

    @Test
    public void testScriptSerializationWithCLTVPaymentChannel() throws IOException {
        // Create a CLTV payment channel script
        BigInteger time = BigInteger.valueOf(1000);
        Script script = ScriptBuilder.createCLTVPaymentChannelOutput(time, key1, key2);
        
        // Serialize the script
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        
        // Deserialize the script
        Script deserializedScript = new Script(serialized);
        
        // Verify the deserialized script is equivalent
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
        assertTrue(deserializedScript.isSentToCLTVPaymentChannel());
        assertEquals(time, deserializedScript.getCLTVPaymentChannelExpiry());
    }

    @Test
    public void testScriptSerializationWithEmptyScript() throws IOException {
        // Create an empty script
        Script script = new ScriptBuilder().build();
        
        // Serialize the script
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertEquals(0, serialized.length);
        
        // Deserialize the script
        Script deserializedScript = new Script(serialized);
        
        // Verify the deserialized script is equivalent
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
        assertEquals(0, deserializedScript.getChunks().size());
    }

    @Test
    public void testScriptSerializationWithComplexScript() throws IOException {
        // Create a complex script with multiple operations
        Script script = new ScriptBuilder()
                .op(ScriptOpCodes.OP_DUP)
                .op(ScriptOpCodes.OP_HASH160)
                .data(Utils.sha256hash160(key1.getPubKey()))
                .op(ScriptOpCodes.OP_EQUALVERIFY)
                .op(ScriptOpCodes.OP_CHECKSIG)
                .build();
        
        // Serialize the script
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        
        // Deserialize the script
        Script deserializedScript = new Script(serialized);
        
        // Verify the deserialized script is equivalent
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
        assertTrue(deserializedScript.isSentToAddress());
    }

    @Test
    public void testScriptSerializationWithLargeData() throws IOException {
        // Create a script with large data
        byte[] largeData = new byte[500]; // 500 bytes of data
        Arrays.fill(largeData, (byte) 0xAB);
        Script script = new ScriptBuilder().data(largeData).build();
        
        // Serialize the script
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        
        // Deserialize the script
        Script deserializedScript = new Script(serialized);
        
        // Verify the deserialized script is equivalent
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
        assertEquals(1, deserializedScript.getChunks().size());
        assertArrayEquals(largeData, deserializedScript.getChunks().get(0).data);
    }

    @Test
    public void testScriptSerializationWithMultipleChunks() throws IOException {
        // Create a script with multiple chunks
        Script script = new ScriptBuilder()
                .smallNum(1)
                .smallNum(2)
                .op(ScriptOpCodes.OP_ADD)
                .smallNum(3)
                .op(ScriptOpCodes.OP_EQUAL)
                .build();
        
        // Serialize the script
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        
        // Deserialize the script
        Script deserializedScript = new Script(serialized);
        
        // Verify the deserialized script is equivalent
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
        assertEquals(5, deserializedScript.getChunks().size());
    }

    @Test
    public void testScriptSerializationRoundTrip() throws IOException {
        // Test round-trip serialization for various script types
        Script[] scripts = {
                ScriptBuilder.createOutputScript(  Address.fromHash160(PARAMS, Utils.sha256hash160(key1.getPubKey()))),
                ScriptBuilder.createOutputScript(key1),
                ScriptBuilder.createMultiSigOutputScript(2, Arrays.asList(key1, key2, key3)),
                ScriptBuilder.createP2SHOutputScript(new byte[20]),
                ScriptBuilder.createCLTVPaymentChannelOutput(BigInteger.valueOf(2000), key1, key2),
                new ScriptBuilder().op(ScriptOpCodes.OP_TRUE).build(),
                new ScriptBuilder().data(new byte[]{0x01, 0x02, 0x03}).build()
        };
        
        for (Script originalScript : scripts) {
            // Serialize
            byte[] serialized = originalScript.getProgram();
            
            // Deserialize
            Script deserializedScript = new Script(serialized);
            
            // Verify round-trip
            assertEquals(originalScript.toString(), deserializedScript.toString());
            assertArrayEquals(originalScript.getProgram(), deserializedScript.getProgram());
        }
    }

    @Test
    public void testScriptChunksPreservation() throws IOException {
        // Create a script and verify chunks are preserved during serialization/deserialization
        Script originalScript = ScriptBuilder.createMultiSigOutputScript(2, Arrays.asList(key1, key2));
        
        // Get original chunks
        List<ScriptChunk> originalChunks = originalScript.getChunks();
        
        // Serialize and deserialize
        byte[] serialized = originalScript.getProgram();
        Script deserializedScript = new Script(serialized);
        
        // Get deserialized chunks
        List<ScriptChunk> deserializedChunks = deserializedScript.getChunks();
        
        // Verify chunks are preserved
        assertEquals(originalChunks.size(), deserializedChunks.size());
        for (int i = 0; i < originalChunks.size(); i++) {
            ScriptChunk originalChunk = originalChunks.get(i);
            ScriptChunk deserializedChunk = deserializedChunks.get(i);
            
            assertEquals(originalChunk.opcode, deserializedChunk.opcode);
            if (originalChunk.data != null && deserializedChunk.data != null) {
                assertArrayEquals(originalChunk.data, deserializedChunk.data);
            } else {
                assertEquals(originalChunk.data, deserializedChunk.data);
            }
        }
    }

    @Test
    public void testScriptWithSignatures() throws IOException {
        Script script = ScriptBuilder.createOutputScript(key1);
        byte[] serialized = script.getProgram();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        Script deserializedScript = new Script(serialized);
        assertEquals(script.toString(), deserializedScript.toString());
        assertArrayEquals(script.getProgram(), deserializedScript.getProgram());
    }
}
