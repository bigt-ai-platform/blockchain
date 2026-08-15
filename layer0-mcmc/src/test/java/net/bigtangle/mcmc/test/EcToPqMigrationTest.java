package net.bigtangle.mcmc.test;

import java.math.BigInteger;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.PQKey;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.wallet.Wallet;

/**
 * Migration path: legacy ECKey funds are moved to a PQKey via a normal
 * spend-and-send transaction. The EC-signed input must verify (ECDSA/secp256k1)
 * and the output is created for a PQ key.
 */
public class EcToPqMigrationTest extends AbstractIntegrationTest {

    @Test
    public void testMigrateEcKeyToPqKey() throws Exception {
        // 1. Create a legacy EC key and a destination PQ key
        ECKey ecKey = ECKey.createNew();
        Wallet ecWallet = Wallet.fromKeys(networkParameters, ecKey, contextRoot);
        String ecAddress = ecKey.toAddress(networkParameters).toBase58();

        PQKey pqKey = PQKey.createNew();

        // 2. Fund the EC key from the genesis wallet
        BigInteger fundAmount = Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(20));
        HashMap<String, BigInteger> give = new HashMap<>();
        give.put(ecAddress, fundAmount);
        wallet.payMoneyToECKeyList(null, give, "fund legacy ec key");

        Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        Block b = drainMempoolAndCreateBlock(predecessor, predecessor);
        if (b != null) {
            makeRewardBlock(b);
        }

        // EC key should now hold funds
        checkBalance(new Coin(fundAmount, NetworkParameters.BIGTANGLE_TOKENID), ecKey);

        // 3. EC wallet migrates the funds to the PQ key via the wallet API
        BigInteger sendAmount = Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(10));
        ecWallet.pay(null, pqKey, new Coin(sendAmount, NetworkParameters.BIGTANGLE_TOKENID), "migrate to PQ");

        predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        b = drainMempoolAndCreateBlock(predecessor, predecessor);
        if (b != null) {
            makeRewardBlock(b);
        }

        // 4. PQ key should now hold the migrated funds
        checkBalance(new Coin(sendAmount, NetworkParameters.BIGTANGLE_TOKENID), pqKey);
    }
}
