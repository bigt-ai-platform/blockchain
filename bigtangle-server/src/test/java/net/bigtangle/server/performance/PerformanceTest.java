/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.performance;

import java.io.StringWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.google.common.base.Stopwatch;

import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.TokensumsMap;
import net.bigtangle.core.Utils;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.ErrorResponse;
import net.bigtangle.server.test.ContractTest;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PerformanceTest extends ContractTest {

	@Test
	public void testReward() throws Exception {
		createUserkey();
		List<Block> a2 = new ArrayList<Block>();
		TokensumsMap c = checkSum(null);
		// second chain
		// usernumber=2000;
		prepare("12200", a2);
		for (int i = 0; i < 12200; i++) {
			createReward(a2);
			// c = checkSum(c);
		}
	}

	@Test
	public void testPrepare() throws Exception {
		List<Block> a2 = new ArrayList<Block>();
		wallet.importKey(ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv)));
		createTestToken(a2);
		createTestContractTokens(a2);
		setcontracttoken();
		ulist = createUserkey();
		checkSum(null,false);
		for (int i = 0; i < 20; i++) {
			ExecutorService executor = Executors.newFixedThreadPool(10);
			@SuppressWarnings("rawtypes")
			Callable callable = new Callable() {
				@Override
				public String call() {
					try {
						payUserKeys(ulist, "1", a2);
						payBigUserKeys(ulist, 1l, a2);
					} catch (Exception e) {
						e.printStackTrace();
					}
					return "";

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
		
		checkSum(null,false);
	}

	public void createReward(List<Block> blocksAddedAll) throws Exception {

		ExecutorService executor = Executors.newFixedThreadPool(10);
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
			payUserKeys(ulist, "1", blocksAddedAll);
			payBigUserKeys(ulist, 1l, blocksAddedAll);
		} catch (Exception e) {
			e.printStackTrace();
		}
		try {
			ordermatch(blocksAddedAll);
		} catch (Exception e) {
			e.printStackTrace();
		}

		try {
			contractExecution(blocksAddedAll);
		} catch (Exception e) {
			e.printStackTrace();

		}
		try {

			// checkSum(null);
			return "";
		} catch (Exception e) {
			e.printStackTrace();
			return "";
		}
	}

	public void payUserKeysRepeat(List<ECKey> userkeys, int factor, List<Block> blocksAddedAll) throws Exception {

		Stopwatch watch = Stopwatch.createStarted();
		List<List<ECKey>> parts = Wallet.chopped(userkeys, 1000);

		for (int i = 0; i < factor; i++) {
			for (List<ECKey> list : parts) {
				HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
				for (ECKey key : list) {
					giveMoneyResult.put(key.toAddress(networkParameters).toString(), payContractAmount);
				}
				Block b = wallet.payToList(null, giveMoneyResult, Utils.HEX.decode(yuanTokenPub), "pay yuan to user");
				// log.debug("block " + (b == null ? "block is null" : b.toString()));
				rewardWithBlock(blocksAddedAll, b);
			}
		}

	}
}