package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.UtilGeneseBlock.GenesisOutput;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;

public class GenesisDistributionTest {

    @Test
    public void testPerAddressDistribution() {
        NetworkParameters params = MainNetParams.get();
        PQKey a = PQKey.createNew();
        PQKey b = PQKey.createNew();

        List<GenesisOutput> distribution = new ArrayList<>();
        distribution.add(GenesisOutput.toPubkey(BigInteger.valueOf(1_000), Utils.HEX.encode(a.getPubKey())));
        distribution.add(GenesisOutput.toPubkey(BigInteger.valueOf(2_000), Utils.HEX.encode(b.getPubKey())));

        Block genesis = UtilGeneseBlock.createGenesis(params, distribution);
        Transaction coinbase = genesis.getTransactions().get(0);

        assertEquals(2, coinbase.getOutputs().size());
        assertEquals(BigInteger.valueOf(1_000), coinbase.getOutput(0).getValue().getValue());
        assertEquals(BigInteger.valueOf(2_000), coinbase.getOutput(1).getValue().getValue());
    }

    @Test
    public void testDistributionSumMatchesTotalSupply() {
        NetworkParameters params = MainNetParams.get();
        PQKey a = PQKey.createNew();
        PQKey b = PQKey.createNew();

        BigInteger total = NetworkParameters.BigtangleCoinTotal;
        List<GenesisOutput> distribution = new ArrayList<>();
        distribution.add(GenesisOutput.toPubkey(total.divide(BigInteger.TWO), Utils.HEX.encode(a.getPubKey())));
        distribution.add(GenesisOutput.toPubkey(total.subtract(total.divide(BigInteger.TWO)), Utils.HEX.encode(b.getPubKey())));

        Block genesis = UtilGeneseBlock.createGenesis(params, distribution);
        BigInteger sum = BigInteger.ZERO;
        for (TransactionOutput out : genesis.getTransactions().get(0).getOutputs())
            sum = sum.add(out.getValue().getValue());
        assertEquals(total, sum);
    }

    @Test
    public void testEmptyDistributionFallsBackToGenesisPub() {
        NetworkParameters params = MainNetParams.get();
        Block withList = UtilGeneseBlock.createGenesis(params, new ArrayList<>());
        Block legacy = UtilGeneseBlock.createGenesis(params);
        assertEquals(legacy.getHash(), withList.getHash());
        assertTrue(legacy.getTransactions().get(0).getOutputs().size() >= 1);
    }

    @Test
    public void testLoadGenesisOutputsFromCsv() throws Exception {
        NetworkParameters params = MainNetParams.get();
        PQKey a = PQKey.createNew();
        PQKey b = PQKey.createNew();
        String addrB = Address.fromHash160(params, b.getPubKeyHash()).toBase58();

        Path f = Files.createTempFile("genesis", ".csv");
        Files.write(f, Arrays.asList(
                "address,pubkey,value",
                "," + Utils.HEX.encode(a.getPubKey()) + ",111",
                addrB + ",,222"));

        List<GenesisOutput> list = UtilGeneseBlock.loadGenesisOutputsFromCsv(f.toString());
        assertEquals(2, list.size());
        assertNotNull(list.get(0).pubkeyHex);
        assertNull(list.get(0).address);
        assertEquals(BigInteger.valueOf(111), list.get(0).amount);
        assertNotNull(list.get(1).address);
        assertNull(list.get(1).pubkeyHex);
        assertEquals(BigInteger.valueOf(222), list.get(1).amount);
    }

    @Test
    public void testCreateGenesisUsesCsvProperty() throws Exception {
        NetworkParameters params = MainNetParams.get();
        PQKey a = PQKey.createNew();
        Path f = Files.createTempFile("genesis", ".csv");
        Files.write(f, Arrays.asList(
                "address,pubkey,value",
                "," + Utils.HEX.encode(a.getPubKey()) + ",5000"));

        System.setProperty(UtilGeneseBlock.GENESIS_CSV_PROPERTY, f.toString());
        try {
            Block genesis = UtilGeneseBlock.createGenesis(params);
            assertEquals(1, genesis.getTransactions().get(0).getOutputs().size());
            assertEquals(BigInteger.valueOf(5000),
                    genesis.getTransactions().get(0).getOutput(0).getValue().getValue());
        } finally {
            System.clearProperty(UtilGeneseBlock.GENESIS_CSV_PROPERTY);
        }
    }
}
