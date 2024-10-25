/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.performance;

import java.io.StringWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

import com.google.common.math.LongMath;

import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.InsufficientMoneyException;
import net.bigtangle.core.response.AbstractResponse;
import net.bigtangle.core.response.ErrorResponse;
import net.bigtangle.core.response.GetBalancesResponse;
import net.bigtangle.core.response.OrderdataResponse;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.server.test.ContractTest;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PerformanceTest extends ContractTest {

	@Test
	// the switch to longest chain
	public void testReorgMiningReward() throws Exception {
		List<Block> a2 = new ArrayList<Block>();

		// second chain
		// usernumber=2000;
		prepare("12200", a2);
		for (int i = 0; i < 12200; i++) {
			createReward(a2);
		}
	}

	public void createReward(List<Block> blocksAddedAll) throws Exception {

		ExecutorService executor = Executors.newSingleThreadExecutor();
		@SuppressWarnings("rawtypes")
		Callable callable = new Callable() {
			@Override
			public String call() {
				return contractAndOrder(blocksAddedAll);
			}

		};

		final Future<String> handler = executor.submit(callable);
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

	public String contractAndOrder(List<Block> blocksAddedAll) {
		try {
			ordermatch(blocksAddedAll);
		} catch (Exception e) {
			e.printStackTrace();
			return "";
		}
		try {
			contractExecution(blocksAddedAll, true);
			checkSum();
			return "";
		} catch (Exception e) {
			e.printStackTrace();
			return "";
		}
	}
}