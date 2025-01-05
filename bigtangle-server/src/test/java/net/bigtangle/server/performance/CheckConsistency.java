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

public class CheckConsistency extends AbstractIntegrationTest {

	/*
	 * there are different check sums and create DAG and diffs
	 */

	@BeforeEach
	public void setUp() throws Exception {
		contextRoot = "http://localhost:8088/";
		wallet = Wallet.fromKeys(networkParameters, ECKey.fromPrivate(Utils.HEX.decode(testPriv)), contextRoot);
		store = storeService.getStore();
	}

	@Test
	public void testDAG() throws Exception {

		createExecutionDAGRequired("execution", 0, 10000000, false);
	}
	@Test
	public void testChecksum() throws Exception {

		checkSum(null);
	}

}
