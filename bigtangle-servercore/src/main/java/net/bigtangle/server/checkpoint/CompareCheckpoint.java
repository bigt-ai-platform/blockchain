/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.checkpoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.TXReward;
import net.bigtangle.core.Tokensums;
import net.bigtangle.core.UTXO;
import net.bigtangle.server.service.SyncBlockService;

public class CompareCheckpoint {
	public static boolean testnet = false;
	public static String HTTPS_BIGTANGLE_DE = "https://" + (testnet ? "test." : "p.") + "bigtangle.de:"
			+ (testnet ? "8089" : "8088") + "/";
	public static String HTTPS_BIGTANGLE_INFO = "https://" + (testnet ? "test." : "p.") + "bigtangle.info:"
			+ (testnet ? "8089" : "8088") + "/";
	public static String HTTPS_BIGTANGLE_ORG = "https://" + (testnet ? "test." : "p.") + "bigtangle.org:"
			+ (testnet ? "8089" : "8088") + "/";
	public static String HTTPS_BIGTANGLE_LOCAL = "http://" + "localhost:8088/";

	public static String TESTSERVER1 = HTTPS_BIGTANGLE_INFO;

	public static String TESTSERVER2 = HTTPS_BIGTANGLE_ORG;
	protected static final Logger log = LoggerFactory.getLogger(CompareCheckpoint.class);
	@Autowired
	protected SyncBlockService syncBlockService;
	CheckpointService checkpointService;

 

	public void diffThread2() throws Exception {
		// System.setProperty("https.proxyHost",
		// "anwproxy.anwendungen.localnet.de");
		// System.setProperty("https.proxyPort", "3128");

		syncBlockService = new SyncBlockService();
		checkpointService = new CheckpointService();

        do {
            try {
                testComapre();
            } catch (Throwable e) {
                e.printStackTrace();

            }
        } while (true);

	}

	public void testComapre() throws Exception {

		List<TXReward> txreward = syncBlockService.getAllConfirmedReward(TESTSERVER1);
		List<TXReward> txreward2 = syncBlockService.getAllConfirmedReward(TESTSERVER2);
		Map<String, Map<String, Tokensums>> result = new HashMap<String, Map<String, Tokensums>>();

	//	checkpointService.checkToken(TESTSERVER1, result);
	//	checkpointService.checkToken(TESTSERVER2, result);
		log.debug("\n " + TESTSERVER2 + "txreward  " + txreward2.size() + "\n "
				+ txreward2.get(txreward2.size() - 1).getBlockHash() + "\n " + TESTSERVER1 + "   txreward  "
				+ txreward.size() + "\n " + txreward.get(txreward.size() - 1).getBlockHash());
		// log.debug(txreward2.toString());
		// assertTrue(Math.abs(txreward2.size() - txreward.size()) < 20,
		// "Math.abs(txreward2.size " + txreward2.size() + " - txreward.size{} ) < 20" +
		// txreward.size());

		Map<String, Tokensums> r1 = result.get(TESTSERVER1);
		Map<String, Tokensums> r2 = result.get(TESTSERVER2);
		for (Entry<String, Tokensums> a : r1.entrySet()) {
			Tokensums t1 = a.getValue();
			// assertTrue(t1.check(), TESTSERVER1 + " " + t1.toString());
			Tokensums t = r2.get(a.getKey());
			// assertTrue(t.check(), TESTSERVER2 + " " + t.toString());

			compareUTXO(t1, t);
			compareUTXO(t, t1);

			if (txreward2.size() == txreward.size()) {
				// assertTrue(t1.equals(t) || t1.unspentOrderSum().equals(t.unspentOrderSum()),
				// "\n " + TESTSERVER1 + ": " + t1.toString() + "\n " + TESTSERVER2 + ": " + t);

			}
		}

		if (txreward2.size() == txreward.size())
			log.debug(" no difference \n" + r1.toString() + " \n " + r2.toString());

	}

	private void compareUTXO(Tokensums t1, Tokensums t) {

		log.debug("\n " + t1.toString() + "\n " + TESTSERVER1 + " utxo size : " + t1.getUtxos().size() + "\n "
				+ TESTSERVER2 + ": " + t.getUtxos().size());

		for (UTXO a : t1.getUtxos()) {
			UTXO find = find(t.getUtxos(), a);
			if (find == null) {
				log.error("\n " + " not found " + a);
			}
		}
	}
 
	private void compareUTXO(List<UTXO> t1, List<UTXO> t2) {

		log.debug("\n " + TESTSERVER1 + " utxo size : " + t1.size() + "\n " + TESTSERVER2 + ": " + t2.size());
		if (t1.size() != t2.size())
			return;
		for (int i = 0; i < t1.size(); i++) {
			UTXO a = t1.get(i);
			UTXO u = t2.get(i);
			if (a.getBlockHashHex().equals(u.getBlockHashHex()) && a.getIndex() == u.getIndex()
					&& a.getValue().equals(u.getValue())) {
				// log.debug("\n " + a.toString());
			} else {
				log.error("\n " + " difference " + a + "\n" + u);
			}
		}

		log.debug("Finish compareUTXO");

	}

	private UTXO find(List<UTXO> t1, UTXO u) {

		for (UTXO a : t1) {
			if (a.getBlockHashHex().equals(u.getBlockHashHex()) && a.getIndex() == u.getIndex()
					&& a.getValue().equals(u.getValue()))
				return a;
			;
		}
		return null;
	}
}