/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;

public class TransactionOutPointSerializationTest {
    private static final NetworkParameters PARAMS = MainNetParams.get();

    @Test
    public void testTransactionOutPointSerialization() throws IOException {
        // Create specific block and transaction hashes
        Sha256Hash blockHash = Sha256Hash.wrap("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f");
        Sha256Hash txHash = Sha256Hash.wrap("1a2b3c4d5e6f7890123456789012345678901234567890123456789012345678");
        long outputIndex = 2L;
        
        // Create an outpoint
        TransactionOutPoint outpoint = TransactionOutPoint.fromTransactionOutPoint4(PARAMS, outputIndex, blockHash, txHash);
        
        // Serialize the outpoint
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        outpoint.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the outpoint
        TransactionOutPoint deserializedOutpoint = TransactionOutPoint.fromTransactionOutPoint5(PARAMS, serialized, 0, null, PARAMS.getDefaultSerializer());
        
        // Verify the deserialized outpoint has the same properties
        assertEquals(blockHash, deserializedOutpoint.getBlockHash());
        assertEquals(txHash, deserializedOutpoint.getTxHash());
        assertEquals(outputIndex, deserializedOutpoint.getIndex());
    }

    @Test
    public void testTransactionOutPointSerializationWithDifferentIndices() throws IOException {
        // Create specific block and transaction hashes
        Sha256Hash blockHash = Sha256Hash.wrap("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f");
        Sha256Hash txHash = Sha256Hash.wrap("1a2b3c4d5e6f7890123456789012345678901234567890123456789012345678");
        
        // Test with various indices
        long[] indices = {0L, 1L, 100L, Integer.MAX_VALUE, 4294967295L};
        
        for (long index : indices) {
            TransactionOutPoint outpoint = TransactionOutPoint.fromTransactionOutPoint4(PARAMS, index, blockHash, txHash);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            outpoint.bitcoinSerialize(bos);
            byte[] serialized = bos.toByteArray();
            
            TransactionOutPoint deserializedOutpoint = TransactionOutPoint.fromTransactionOutPoint5(PARAMS, serialized, 0, null, PARAMS.getDefaultSerializer());
            
            assertEquals(index, deserializedOutpoint.getIndex());
            assertEquals(blockHash, deserializedOutpoint.getBlockHash());
            assertEquals(txHash, deserializedOutpoint.getTxHash());
        }
    }

    @Test
    public void testTransactionOutPointSerializationWithCoinbase() throws IOException {
        // Create a coinbase outpoint
        TransactionOutPoint outpoint = TransactionOutPoint.fromTransactionOutPoint4(PARAMS, 4294967295L, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH);
        
        // Verify it's recognized as coinbase
        assertEquals(true, outpoint.isCoinBase());
        
        // Serialize the outpoint
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        outpoint.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the outpoint
        TransactionOutPoint deserializedOutpoint = TransactionOutPoint.fromTransactionOutPoint5(PARAMS, serialized, 0, null, PARAMS.getDefaultSerializer());
        
        // Verify the deserialized outpoint is still coinbase
        assertEquals(true, deserializedOutpoint.isCoinBase());
        assertEquals(Sha256Hash.ZERO_HASH, deserializedOutpoint.getBlockHash());
        assertEquals(Sha256Hash.ZERO_HASH, deserializedOutpoint.getTxHash());
        assertEquals(4294967295L, deserializedOutpoint.getIndex());
    }

    @Test
    public void testTransactionOutPointSerializationRoundTrip() throws IOException {
        // Create specific block and transaction hashes
        Sha256Hash blockHash = Sha256Hash.wrap("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
        Sha256Hash txHash = Sha256Hash.wrap("0987654321fedcba0987654321fedcba0987654321fedcba0987654321fedcba");
        long outputIndex = 42L;
        
        // Create an outpoint
        TransactionOutPoint outpoint = TransactionOutPoint.fromTransactionOutPoint4(PARAMS, outputIndex, blockHash, txHash);
        
        // Serialize the outpoint
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        outpoint.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the outpoint
        TransactionOutPoint deserializedOutpoint = TransactionOutPoint.fromTransactionOutPoint5(PARAMS, serialized, 0, null, PARAMS.getDefaultSerializer());
        
        // Verify all properties are preserved
        assertEquals(blockHash, deserializedOutpoint.getBlockHash());
        assertEquals(txHash, deserializedOutpoint.getTxHash());
        assertEquals(outputIndex, deserializedOutpoint.getIndex());
        assertEquals(outpoint.getHash(), deserializedOutpoint.getHash());
    }

    @Test
    public void testTransactionOutPointSerializationWithConnectedOutput() throws IOException {
        // Create specific block and transaction hashes
        Sha256Hash blockHash = Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111");
        Sha256Hash txHash = Sha256Hash.wrap("2222222222222222222222222222222222222222222222222222222222222222");
        long outputIndex = 7L;
        
        // Create an outpoint
        TransactionOutPoint outpoint = TransactionOutPoint.fromTransactionOutPoint4(PARAMS, outputIndex, blockHash, txHash);
        
        // Create a transaction and output to connect
        Transaction transaction = new Transaction(PARAMS);
        PQKey key = PQKey.createNew();
        Address address = key.toAddress(PARAMS);
        Coin coinValue = Coin.valueOf(100000L, NetworkParameters.BIGTANGLE_TOKENID);
        TransactionOutput connectedOutput = TransactionOutput.fromAddress(PARAMS, transaction, coinValue, address);
        
        // Connect the output to the outpoint (this is normally done internally)
        outpoint.connectedOutput = connectedOutput;
        
        // Serialize the outpoint
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        outpoint.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the outpoint
        TransactionOutPoint deserializedOutpoint = TransactionOutPoint.fromTransactionOutPoint5(PARAMS, serialized, 0, null, PARAMS.getDefaultSerializer());
        
        // Verify the basic properties are preserved
        assertEquals(blockHash, deserializedOutpoint.getBlockHash());
        assertEquals(txHash, deserializedOutpoint.getTxHash());
        assertEquals(outputIndex, deserializedOutpoint.getIndex());
    }

    @Test
    public void testTransactionOutPointSerializationWithZeroHashes() throws IOException {
        // Create an outpoint with zero hashes
        Sha256Hash blockHash = Sha256Hash.ZERO_HASH;
        Sha256Hash txHash = Sha256Hash.ZERO_HASH;
        long outputIndex = 0L;
        
        // Create an outpoint
        TransactionOutPoint outpoint = TransactionOutPoint.fromTransactionOutPoint4(PARAMS, outputIndex, blockHash, txHash);
        
        // Serialize the outpoint
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        outpoint.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        // Deserialize the outpoint
        TransactionOutPoint deserializedOutpoint = TransactionOutPoint.fromTransactionOutPoint5(PARAMS, serialized, 0, null, PARAMS.getDefaultSerializer());
        
        // Verify the deserialized outpoint has the same properties
        assertEquals(blockHash, deserializedOutpoint.getBlockHash());
        assertEquals(txHash, deserializedOutpoint.getTxHash());
        assertEquals(outputIndex, deserializedOutpoint.getIndex());
    }

    @Test
    public void testTransactionOutPointGetConnectedOutput() {
        // Create an outpoint
        Sha256Hash blockHash = Sha256Hash.wrap("3333333333333333333333333333333333333333333333333333333333333333");
        Sha256Hash txHash = Sha256Hash.wrap("4444444444444444444444444444444444444444444444444444444444444444");
        long outputIndex = 3L;
        
        TransactionOutPoint outpoint = TransactionOutPoint.fromTransactionOutPoint4(PARAMS, outputIndex, blockHash, txHash);
        
        // Initially, there should be no connected output
        assertNull(outpoint.getConnectedOutput());
        
        // Create a transaction and output to connect
        Transaction transaction = new Transaction(PARAMS);
        PQKey key = PQKey.createNew();
        Address address = key.toAddress(PARAMS);
        Coin coinValue = Coin.valueOf(50000L, NetworkParameters.BIGTANGLE_TOKENID);
        TransactionOutput connectedOutput = TransactionOutput.fromAddress(PARAMS, transaction, coinValue, address);
        
        // Connect the output to the outpoint
        outpoint.connectedOutput = connectedOutput;
        
        // Now there should be a connected output
        assertEquals(connectedOutput, outpoint.getConnectedOutput());
    }

    @Test
    public void testTransactionOutPointEquality() throws IOException {
        // Create two identical outpoints
        Sha256Hash blockHash = Sha256Hash.wrap("5555555555555555555555555555555555555555555555555555555555555555");
        Sha256Hash txHash = Sha256Hash.wrap("6666666666666666666666666666666666666666666666666666666666666666");
        long outputIndex = 5L;
        
        TransactionOutPoint outpoint1 = TransactionOutPoint.fromTransactionOutPoint4(PARAMS, outputIndex, blockHash, txHash);
        TransactionOutPoint outpoint2 = TransactionOutPoint.fromTransactionOutPoint4(PARAMS, outputIndex, blockHash, txHash);
        
        // They should be equal
        assertEquals(outpoint1, outpoint2);
        assertEquals(outpoint1.hashCode(), outpoint2.hashCode());
        
        // Serialize and deserialize one of them
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        outpoint1.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        TransactionOutPoint deserializedOutpoint = TransactionOutPoint.fromTransactionOutPoint5(PARAMS, serialized, 0, null, PARAMS.getDefaultSerializer());
        
        // The deserialized one should be equal to the original
        assertEquals(outpoint1, deserializedOutpoint);
        assertEquals(outpoint1.hashCode(), deserializedOutpoint.hashCode());
    }
}
