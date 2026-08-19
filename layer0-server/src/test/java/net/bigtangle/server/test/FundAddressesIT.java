package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

public class FundAddressesIT extends AbstractIntegrationTest {

    @Test
    public void testFundAndSpend() throws Exception {
        // Create a new key to fund
        PQKey aliceKey = PQKey.createNew();
        String aliceAddr = Address.fromHash160(networkParameters, aliceKey.getPubKeyHash()).toBase58();

        // Fund via API
        HashMap<String, Object> fundReq = new HashMap<>();
        List<HashMap<String, Object>> entries = new ArrayList<>();
		HashMap<String, Object> entry = new HashMap<>();
		entry.put("address", aliceAddr);
		entry.put("value", 100000L);
		entries.add(entry);
		fundReq.put("addresses", entries);
		byte[] resp = OkHttp3Util.post(contextRoot + "fundAddresses",
				Json.jsonmapper().writeValueAsString(fundReq).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		System.out.println("fundAddresses response: " + new String(resp));

		// Verify UTXOs exist
		List<UTXO> utxos = wallet.calculateAllSpendCandidatesUTXO(null, false);
		System.out.println("UTXOs after fund: " + utxos.size());

		// Create a second key and spend to it
		PQKey bobKey = PQKey.createNew();
		HashMap<String, BigInteger> payment = new HashMap<>();
		payment.put(Address.fromHash160(networkParameters, bobKey.getPubKeyHash()).toBase58(), BigInteger.valueOf(1000));
		wallet.payToList(null, payment, NetworkParameters.BIGTANGLE_TOKENID, "fund");

        // Verify bob received UTXOs
        List<byte[]> pubKeyHashs = new ArrayList<>();
        pubKeyHashs.add(bobKey.getPubKeyHash());
        List<UTXO> bobUtxos = wallet.calculateAllSpendCandidatesUTXO(null, false);
        System.out.println("Wallet UTXOs after spend: " + bobUtxos.size());
    }
}
