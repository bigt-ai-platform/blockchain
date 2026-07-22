/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;

public class TransactionOutputSerializationTest {
    private static final NetworkParameters PARAMS = MainNetParams.get();
    private Transaction transaction;
    private PQKey key;
    private Address address;

    @BeforeEach
    public void setUp() throws Exception {
        transaction = new Transaction(PARAMS);
        key = PQKey.createNew();
        address = Address.fromHash160(PARAMS, Utils.sha256hash160(key.getPubKey()));
    }

    @Test
    public void testTransactionOutputSerializationWithCoin() throws IOException {
        // Create a transaction output with a specific coin value
        Coin coinValue = Coin.valueOf(1000000L, NetworkParameters.BIGTANGLE_TOKENID);
        TransactionOutput output = TransactionOutput.fromAddress(PARAMS, transaction, coinValue, address);
        
        // Add the output to the transaction so getIndex() works
        transaction.addOutput(output);
        
        // Verify the output has the correct coin value
        assertEquals(coinValue, output.getValue());
        
        // Serialize the output
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        output.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the output
        TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(PARAMS, transaction, serialized, 0, PARAMS.getDefaultSerializer());
        
        // Verify the deserialized output has the same properties
        assertEquals(coinValue, deserializedOutput.getValue());
        // Note: We can't check getIndex() because the deserialized output is not attached to a transaction
    }

    @Test
    public void testTransactionOutputSerializationWithDifferentCoinValues() throws IOException {
        // Test with various coin values
        long[] values = {1L, 1000L, 1000000L, Long.MAX_VALUE};
        
        for (long value : values) {
            Coin coinValue = Coin.valueOf(value, NetworkParameters.BIGTANGLE_TOKENID);
            TransactionOutput output = TransactionOutput.fromAddress(PARAMS, transaction, coinValue, address);
            
            // Add the output to the transaction so getIndex() works
            transaction.addOutput(output);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            output.bitcoinSerialize(bos);
            byte[] serialized = bos.toByteArray();
            
            TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(PARAMS, transaction, serialized, 0, PARAMS.getDefaultSerializer());
            
            assertEquals(coinValue, deserializedOutput.getValue());
        }
    }

    @Test
    public void testTransactionOutputSerializationWithScript() throws IOException {
        // Create a transaction output with a specific script
        Coin coinValue = Coin.valueOf(500000L, NetworkParameters.BIGTANGLE_TOKENID);
        TransactionOutput output = TransactionOutput.fromAddress(PARAMS, transaction, coinValue, address);
        
        // Add the output to the transaction so getIndex() works
        transaction.addOutput(output);
        
        // Get the script bytes
        byte[] scriptBytes = output.getScriptBytes();
        
        // Serialize the output
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        output.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the output
        TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(PARAMS, transaction, serialized, 0, PARAMS.getDefaultSerializer());
        
        // Verify the script bytes are preserved
        assertArrayEquals(scriptBytes, deserializedOutput.getScriptBytes());
        assertEquals(coinValue, deserializedOutput.getValue());
    }

    @Test
    public void testTransactionOutputSerializationWithPublicKey() throws IOException {
        // Create a transaction output with a public key
        Coin coinValue = Coin.valueOf(250000L, NetworkParameters.BIGTANGLE_TOKENID);
        TransactionOutput output = TransactionOutput.fromCoinKey(PARAMS, transaction, coinValue, key);
        
        // Serialize the output
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        output.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the output
        TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(PARAMS, transaction, serialized, 0, PARAMS.getDefaultSerializer());
        
        // Verify the output is preserved
        assertEquals(coinValue, deserializedOutput.getValue());
        assertNotNull(deserializedOutput.getScriptBytes());
    }

    @Test
    public void testTransactionOutputSerializationRoundTrip() throws IOException {
        // Create a complex transaction output
        Coin coinValue = Coin.valueOf(999999L, NetworkParameters.BIGTANGLE_TOKENID);
        TransactionOutput output = TransactionOutput.fromAddress(PARAMS, transaction, coinValue, address);
        
        // Add the output to the transaction so getIndex() works
        transaction.addOutput(output);
        
        // Serialize the output
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        output.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the output
        TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(PARAMS, transaction, serialized, 0, PARAMS.getDefaultSerializer());
        
        // Verify all properties are preserved
        assertEquals(coinValue, deserializedOutput.getValue());
        assertArrayEquals(output.getScriptBytes(), deserializedOutput.getScriptBytes());
    }

    @Test
    public void testTransactionOutputSerializationWithZeroValue() throws IOException {
        // Create a transaction output with zero value
        Coin coinValue = Coin.valueOf(0L, NetworkParameters.BIGTANGLE_TOKENID);
        TransactionOutput output = TransactionOutput.fromAddress(PARAMS, transaction, coinValue, address);
        
        // Add the output to the transaction so getIndex() works
        transaction.addOutput(output);
        
        // Serialize the output
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        output.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the output
        TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(PARAMS, transaction, serialized, 0, PARAMS.getDefaultSerializer());
        
        // Verify the zero value is preserved
        assertEquals(coinValue, deserializedOutput.getValue());
        assertEquals(0, deserializedOutput.getValue().getValue().compareTo(BigInteger.ZERO));
    }

    @Test
    public void testTransactionOutputSerializationWithDifferentTokenIds() throws IOException {
        // Test with different token IDs
        byte[][] tokenIds = {
            NetworkParameters.BIGTANGLE_TOKENID,
            Utils.HEX.decode("0000000000000000000000000000000000000000000000000000000000000001"),
            Utils.HEX.decode("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        };
        
        for (byte[] tokenId : tokenIds) {
            Coin coinValue = Coin.valueOf(123456L, tokenId);
            TransactionOutput output = TransactionOutput.fromAddress(PARAMS, transaction, coinValue, address);
            
            // Add the output to the transaction so getIndex() works
            transaction.addOutput(output);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            output.bitcoinSerialize(bos);
            byte[] serialized = bos.toByteArray();
            
            TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(PARAMS, transaction, serialized, 0, PARAMS.getDefaultSerializer());
            
            assertEquals(coinValue, deserializedOutput.getValue());
            assertArrayEquals(tokenId, deserializedOutput.getValue().getTokenid());
        }
    }

    @Test
    public void testTransactionOutputSerializationWithLargeValue() throws IOException {
        // Test with a large coin value
        BigInteger largeValue = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(1000));
        Coin coinValue = new Coin(largeValue, NetworkParameters.BIGTANGLE_TOKENID);
        TransactionOutput output = TransactionOutput.fromAddress(PARAMS, transaction, coinValue, address);
        
        // Add the output to the transaction so getIndex() works
        transaction.addOutput(output);
        
        // Serialize the output
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        output.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the output
        TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(PARAMS, transaction, serialized, 0, PARAMS.getDefaultSerializer());
        
        // Verify the large value is preserved
        assertEquals(coinValue, deserializedOutput.getValue());
        assertEquals(largeValue, deserializedOutput.getValue().getValue());
    }
}
