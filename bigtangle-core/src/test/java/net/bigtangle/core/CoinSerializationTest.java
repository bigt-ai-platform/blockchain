/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;

public class CoinSerializationTest {

    @Test
    public void testCoinSerializationWithStandardValues() throws IOException {
        // Test with standard coin values
        long[] values = {0L, 1L, 1000L, 1000000L, Coin.COIN.getValue().longValue(), Long.MAX_VALUE};
        
        for (long value : values) {
            Coin coin = Coin.valueOf(value, NetworkParameters.BIGTANGLE_TOKENID);
            
            // Test serialization and deserialization through TransactionOutput
            Transaction transaction = new Transaction(MainNetParams.get());
            ECKey key = new ECKey();
            Address address = key.toAddress(MainNetParams.get());
            TransactionOutput output = TransactionOutput.fromAddress(MainNetParams.get(), transaction, coin, address);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            output.bitcoinSerialize(bos);
            byte[] serialized = bos.toByteArray();
            
            TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(MainNetParams.get(), transaction, serialized, 0, MainNetParams.get().getDefaultSerializer());
            Coin deserializedCoin = deserializedOutput.getValue();
            
            assertEquals(coin, deserializedCoin);
            assertEquals(coin.getValue(), deserializedCoin.getValue());
            assertArrayEquals(coin.getTokenid(), deserializedCoin.getTokenid());
        }
    }

    @Test
    public void testCoinSerializationWithDifferentTokenIds() throws IOException {
        // Test with different token IDs
        long value = 123456L;
        
        byte[][] tokenIds = {
            NetworkParameters.BIGTANGLE_TOKENID,
            Utils.HEX.decode("0000000000000000000000000000000000000000000000000000000000000001"),
            Utils.HEX.decode("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        };
        
        for (byte[] tokenId : tokenIds) {
            Coin coin = Coin.valueOf(value, tokenId);
            
            // Test serialization and deserialization through TransactionOutput
            Transaction transaction = new Transaction(MainNetParams.get());
            ECKey key = new ECKey();
            Address address = key.toAddress(MainNetParams.get());
            TransactionOutput output = TransactionOutput.fromAddress(MainNetParams.get(), transaction, coin, address);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            output.bitcoinSerialize(bos);
            byte[] serialized = bos.toByteArray();
            
            TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(MainNetParams.get(), transaction, serialized, 0, MainNetParams.get().getDefaultSerializer());
            Coin deserializedCoin = deserializedOutput.getValue();
            
            assertEquals(coin, deserializedCoin);
            assertEquals(coin.getValue().longValue(), deserializedCoin.getValue().longValue());
            assertArrayEquals(tokenId, deserializedCoin.getTokenid());
        }
    }

    @Test
    public void testCoinSerializationWithLargeValues() throws IOException {
        // Test with large coin values
        BigInteger[] values = {
            BigInteger.valueOf(Long.MAX_VALUE),
            BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2)),
            BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(1000))
        };
        
        for (BigInteger value : values) {
            Coin coin = new Coin(value, NetworkParameters.BIGTANGLE_TOKENID);
            
            // Test serialization and deserialization through TransactionOutput
            Transaction transaction = new Transaction(MainNetParams.get());
            ECKey key = new ECKey();
            Address address = key.toAddress(MainNetParams.get());
            TransactionOutput output = TransactionOutput.fromAddress(MainNetParams.get(), transaction, coin, address);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            output.bitcoinSerialize(bos);
            byte[] serialized = bos.toByteArray();
            
            TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(MainNetParams.get(), transaction, serialized, 0, MainNetParams.get().getDefaultSerializer());
            Coin deserializedCoin = deserializedOutput.getValue();
            
            assertEquals(coin, deserializedCoin);
            assertEquals(coin.getValue(), deserializedCoin.getValue());
            assertArrayEquals(coin.getTokenid(), deserializedCoin.getTokenid());
        }
    }


    @Test
    public void testCoinSerializationRoundTrip() throws IOException {
        // Test a complete round trip with a complex coin value
        BigInteger value = BigInteger.valueOf(999999999L);
        byte[] tokenId = Utils.HEX.decode("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
        Coin coin = new Coin(value, tokenId);
        
            // Test serialization and deserialization through TransactionOutput
            Transaction transaction = new Transaction(MainNetParams.get());
            ECKey key = new ECKey();
            Address address = key.toAddress(MainNetParams.get());
            TransactionOutput output = TransactionOutput.fromAddress(MainNetParams.get(), transaction, coin, address);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            output.bitcoinSerialize(bos);
            byte[] serialized = bos.toByteArray();
            
            TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(MainNetParams.get(), transaction, serialized, 0, MainNetParams.get().getDefaultSerializer());
        Coin deserializedCoin = deserializedOutput.getValue();
        
        // Verify all properties are preserved
        assertEquals(coin, deserializedCoin);
        assertEquals(value, deserializedCoin.getValue());
        assertArrayEquals(tokenId, deserializedCoin.getTokenid());
        assertEquals(coin.isPositive(), deserializedCoin.isPositive());
        assertEquals(coin.isNegative(), deserializedCoin.isNegative());
        assertEquals(coin.isZero(), deserializedCoin.isZero());
    }

    @Test
    public void testCoinSerializationWithZeroTokenId() throws IOException {
        // Test with zero token ID
        BigInteger value = BigInteger.valueOf(555555L);
        byte[] tokenId = new byte[32]; // All zeros
        Coin coin = new Coin(value, tokenId);
        
        // Test serialization and deserialization through TransactionOutput
        Transaction transaction = new Transaction(MainNetParams.get());
        ECKey key = new ECKey();
        Address address = key.toAddress(MainNetParams.get());
        TransactionOutput output = TransactionOutput.fromAddress(MainNetParams.get(), transaction, coin, address);
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        output.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(MainNetParams.get(), transaction, serialized, 0, MainNetParams.get().getDefaultSerializer());
        Coin deserializedCoin = deserializedOutput.getValue();
        
        assertEquals(coin, deserializedCoin);
        assertEquals(value, deserializedCoin.getValue());
        assertArrayEquals(tokenId, deserializedCoin.getTokenid());
    }

    @Test
    public void testCoinEqualityAfterSerialization() throws IOException {
        // Test that coins are equal before and after serialization
        BigInteger value = BigInteger.valueOf(777777L);
        byte[] tokenId = NetworkParameters.BIGTANGLE_TOKENID;
        Coin coin1 = new Coin(value, tokenId);
        Coin coin2 = new Coin(value, tokenId);
        
        // They should be equal before serialization
        assertEquals(coin1, coin2);
        assertEquals(coin1.hashCode(), coin2.hashCode());
        
        // Test serialization and deserialization
        Transaction transaction = new Transaction(MainNetParams.get());
        ECKey key = new ECKey();
        Address address = key.toAddress(MainNetParams.get());
        TransactionOutput output = TransactionOutput.fromAddress(MainNetParams.get(), transaction, coin1, address);
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        output.bitcoinSerialize(bos);
        byte[] serialized = bos.toByteArray();
        
        TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(MainNetParams.get(), transaction, serialized, 0, MainNetParams.get().getDefaultSerializer());
        Coin deserializedCoin = deserializedOutput.getValue();
        
        // The deserialized coin should be equal to the original coins
        assertEquals(coin1, deserializedCoin);
        assertEquals(coin2, deserializedCoin);
        assertEquals(coin1.hashCode(), deserializedCoin.hashCode());
    }

    @Test
    public void testCoinSerializationWithSpecialValues() throws IOException {
        // Test with special coin values
        Coin[] specialCoins = {
            Coin.ZERO,
            Coin.COIN,
            Coin.SATOSHI,
         //   Coin.NEGATIVE_SATOSHI,
            Coin.FEE_DEFAULT
        };
        
        for (Coin coin : specialCoins) {
            // Test serialization and deserialization through TransactionOutput
            Transaction transaction = new Transaction(MainNetParams.get());
            ECKey key = new ECKey();
            Address address = key.toAddress(MainNetParams.get());
            TransactionOutput output = TransactionOutput.fromAddress(MainNetParams.get(), transaction, coin, address);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            output.bitcoinSerialize(bos);
            byte[] serialized = bos.toByteArray();
            
            TransactionOutput deserializedOutput = TransactionOutput.fromTransactionOutput(MainNetParams.get(), transaction, serialized, 0, MainNetParams.get().getDefaultSerializer());
            Coin deserializedCoin = deserializedOutput.getValue();
            
            assertEquals(coin, deserializedCoin);
            assertEquals(coin.getValue(), deserializedCoin.getValue());
            assertArrayEquals(coin.getTokenid(), deserializedCoin.getTokenid());
        }
    }
}
