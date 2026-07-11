package net.bigtangle.performance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CheckpointTest extends AbstractIntegrationTest {

    @Autowired
  //  private CheckpointService checkpointService;
  
 

 	public void testGiveMoney() throws Exception {

		ECKey genesiskey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
				Utils.HEX.decode(testPub));
		List<UTXO> balance1 = getBalance(false, genesiskey);
		log.info("balance1 : " + balance1);
		// two utxo to spent
		HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
		for (int i = 0; i < 3; i++) {
			ECKey outKey = new ECKey();
			giveMoneyResult.put(outKey.toAddress(networkParameters).toBase58(), Coin.COIN.getValue() );
		}
		wallet.payMoneyToECKeyList(null, giveMoneyResult, "testGiveMoney");
		makeRewardBlock();

		List<UTXO> balance = getBalance(false, genesiskey);
		log.info("balance : " + balance);
		for (UTXO utxo : balance) {

			assertTrue(utxo.getValue().getValue().equals(NetworkParameters.BigtangleCoinTotal
					.subtract(Coin.COIN.getValue().multiply(BigInteger.valueOf(3))).subtract(Coin.FEE_DEFAULT.getValue())));

		}
	}

}
