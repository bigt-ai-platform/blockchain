package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.TestParams;

public class GenesisHashTest {

    private static class ParamsWithChainId extends MainNetParams {
        ParamsWithChainId(String chainId) {
            super();
            this.chainId = chainId;
        }
    }

    @Test
    public void testDifferentChainIdsProduceDifferentGenesisHashes() {
        Block genesisL0 = UtilGeneseBlock.createGenesis(new ParamsWithChainId("L0"));
        Block genesisL1 = UtilGeneseBlock.createGenesis(new ParamsWithChainId("L1"));
        assertNotEquals(genesisL0.getHash(), genesisL1.getHash(),
                "Genesis hashes must differ when chainId differs; "
                + "UtilGeneseBlock.createGenesis must incorporate chainId into the coinbase script");
    }

    @Test
    public void testSameChainIdProducesSameGenesisHash() {
        Block a = UtilGeneseBlock.createGenesis(new ParamsWithChainId("same"));
        Block b = UtilGeneseBlock.createGenesis(new ParamsWithChainId("same"));
        assertEquals(a.getHash(), b.getHash());
    }
}
