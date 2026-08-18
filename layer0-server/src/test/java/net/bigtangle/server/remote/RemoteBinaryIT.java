/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.BitcoinSerializer;
import net.bigtangle.core.Block;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;

public class RemoteBinaryIT extends RemoteTestBase {

	@Test
	public void testSerial() throws Exception {
		String tip = "0100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000ecb7e82f91dc35ddc2928c27924a1fa773b37b754659605d42315b467c22b5b5ebed816a00000000000000000000000018424c4f434b545950455f544f4b454e5f4352454154494f4e0000000000000000020100000000010203e801bc1976a9141d65081bed5cd3f907d57df193af2f8ec325e36a88ac0000000000000000000000000000000000000000000000000100000000010207d001bc1976a9141d65081bed5cd3f907d57df193af2f8ec325e36a88ac000000000000000000000000000000000000000000000000";

		// Create a serializer with parseRetain set to true
		BitcoinSerializer serializer = networkParameters.getSerializer(true);
		Block block = serializer.makeBlock(Utils.HEX.decode(tip));

		assertEquals(tip, Utils.HEX.encode(block.bitcoinSerialize()));

		java.util.List<Transaction> transactions = block.getTransactions();
		if (transactions != null) {
			int txIndex = 0;
			for (Transaction t : transactions) {
				net.bigtangle.core.Sha256Hash hash = t.getHash();
				System.out.println("Transaction " + txIndex + " hash: " + hash.toString());
				txIndex++;
			}
		}

		System.out.println(block.toString());
	}
}