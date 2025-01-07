package net.bigtangle.server.performance;

import java.io.StringWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Utils;
import net.bigtangle.core.response.AbstractResponse;
import net.bigtangle.core.response.ErrorResponse;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.test.AbstractIntegrationTest;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)

public class PerformanceRemote extends AbstractIntegrationTest {

	/*
	 * run ContractTest.testPay() Start the test Server run the testProcess
	 */
	public static String lotteryTokenPriv = "6cecae9a820844dac41521ddad4f1b5068fdcac59ce28a6dd1ed01a12f782362";
	public ECKey contractKey = ECKey.fromPrivate(Utils.HEX.decode(lotteryTokenPriv));
	String contractAmount = "2500";
	public BigInteger payContractAmount = new BigInteger(contractAmount);

	@BeforeEach
	public void setUp() throws Exception {
		contextRoot = "http://localhost:8088/";
		wallet = Wallet.fromKeys(networkParameters, ECKey.fromPrivate(Utils.HEX.decode(testPriv)), contextRoot);
		store = storeService.getStore();
	}

	@Test
	public void testProcess() throws Exception {
		List<Block> a2 = new ArrayList<>();
		for (int i = 0; i < 2200; i++) {
			create(a2);
		}
	}

	@Test
	public void testDAG() throws Exception {

		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);

		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		long cutoffHeight = serviceBase.getCurrentCutoffHeight(maxConfirmedReward, store);
		long maxHeight = serviceBase.getCurrentMaxHeight(maxConfirmedReward, store);
		createDAG("testDAG", cutoffHeight, maxHeight);
	}

	@Test
	public void testUnconfirm() throws Exception {
		//checkSum(null);
		blockGraph.updateUnConfirmedDo(store);
		checkSum(null,true);
	}

	public void create(List<Block> a2) throws Exception {

		ExecutorService executor = Executors.newSingleThreadExecutor();
		@SuppressWarnings("rawtypes")
		Callable callable = () -> contractAndOrder(a2);

		final Future handler = executor.submit(callable);
		try {
			handler.get(30, TimeUnit.MINUTES);
		} catch (Exception e) {
			// logger.debug(" process Timeout ");
			handler.cancel(true);
			AbstractResponse resp = ErrorResponse.create(100);
			StringWriter sw = new StringWriter();
			resp.setMessage(sw.toString());
		} finally {
			executor.shutdownNow();
		}

	}

	public String contractAndOrder(List<Block> a1) {
		try {
			sell(a1);
			buy(a1);
		} catch (Exception e) {
			e.printStackTrace();
			return "";
		}
		try {
			for (ECKey key : createUserkey()) {
				Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
				a1.add(w.payContract(null, yuanTokenPub, payContractAmount, null, null,
						contractKey.getPublicKeyAsHex()));
			}
			return "";
		} catch (Exception e) {
			e.printStackTrace();
			return "";
		}

	}

}
