/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bigtangle.core;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;

/**
 * A collection of various utility methods that are helpful for working with the
 * Bitcoin protocol. To enable debug logging from the library, run with
 * -Dbitcoinj.logging=true on your command line.
 */
public class UtilGeneseBlock { 
	
	/**
	 * System property (or env var) naming a GenesisOutput CSV to replay into the
	 * genesis coinbase. When set, {@link #createGenesis(NetworkParameters)} mints
	 * one output per CSV row instead of the single {@code genesisPub} output.
	 */
	public static final String GENESIS_CSV_PROPERTY = "bigtangle.genesis.csv";
	public static final String GENESIS_CSV_ENV = "BIGTANGLE_GENESIS_CSV";

    public static void add(NetworkParameters params, BigInteger amount, String account, Transaction coinbase) {
        // amount, many public keys
        String[] list = account.split(",");
        Coin base = new Coin(amount,NetworkParameters. BIGTANGLE_TOKENID);
        List<PQKey> keys = new ArrayList<>();
        for (String s : list) {
            byte[] pubBytes = Utils.HEX.decode(s.trim());
            // Legacy EC pubkeys (0x02/0x03/0x04 prefix, 33-65 bytes) - wrap in KeyBundle
            if (pubBytes.length > 0 && (pubBytes[0] == 0x02 || pubBytes[0] == 0x03 || pubBytes[0] == 0x04)) {
                List<net.bigtangle.crypto.pq.KeyBundle.Entry> entries = new ArrayList<>();
                entries.add(new net.bigtangle.crypto.pq.KeyBundle.Entry(
                    net.bigtangle.crypto.pq.PQConstants.ALG_ML_DSA_87, pubBytes));
                net.bigtangle.crypto.pq.KeyBundle bundle = new net.bigtangle.crypto.pq.KeyBundle(entries);
                keys.add(net.bigtangle.core.PQKey.fromPublicOnly(bundle));
            } else {
                keys.add(PQKey.fromPublicOnly(pubBytes));
            }
        }
	        if (keys.size() <= 1) {
	            coinbase.addOutput(new TransactionOutput(params, coinbase, base,
	                    ScriptBuilder.createOutputScript(PQKey.fromPublicOnly(keys.get(0).getPubKey())).getProgram()));
	        } else {
	            Script scriptPubKey = ScriptBuilder.createMultiSigOutputScript(keys.size() - 1, keys);
	            coinbase.addOutput(new TransactionOutput(params, coinbase, base, scriptPubKey.getProgram()));
	        }
	    }

	/**
	 * A single genesis distribution entry: mint {@code amount} BIG (smallest
	 * units) to either a base58 {@code address} or a {@code pubkeyHex} (exactly
	 * one of the two must be set). Used to replay a legacy balance snapshot into
	 * the PoS genesis coinbase.
	 */
	public static class GenesisOutput {
	    public final BigInteger amount;
	    public final String address;
	    public final String pubkeyHex;

	    public GenesisOutput(BigInteger amount, String address, String pubkeyHex) {
	        this.amount = amount;
	        this.address = address;
	        this.pubkeyHex = pubkeyHex;
	    }

	    public static GenesisOutput toAddress(BigInteger amount, String base58Address) {
	        return new GenesisOutput(amount, base58Address, null);
	    }

	    public static GenesisOutput toPubkey(BigInteger amount, String pubkeyHex) {
	        return new GenesisOutput(amount, null, pubkeyHex);
	    }
	}

	/**
	 * Creates the genesis block minting the total BIG supply to
	 * {@code params.genesisPub} (single key or M-of-N multisig), as before.
	 *
	 * <p>If a GenesisOutput CSV is configured via {@value #GENESIS_CSV_PROPERTY}
	 * (or {@value #GENESIS_CSV_ENV}), the CSV distribution is used instead.
	 */
	public static Block createGenesis(NetworkParameters params) {
	    String csv = System.getProperty(GENESIS_CSV_PROPERTY);
	    if (csv == null || csv.trim().isEmpty())
	        csv = System.getenv(GENESIS_CSV_ENV);
	    if (csv != null && !csv.trim().isEmpty())
	        return createGenesis(params, loadGenesisOutputsFromCsv(csv.trim()));
	    return createGenesis(params, null);
	}

	/**
	 * Creates the genesis block. If {@code distribution} is non-empty, the
	 * coinbase emits one output per entry (amount + base58 address or pubkey);
	 * otherwise it falls back to the legacy single/multisig {@code genesisPub}
	 * output minting the total supply.
	 */
	public static Block createGenesis(NetworkParameters params, List<GenesisOutput> distribution) {
	    Block genesisBlock =   Block.setBlock7(params, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH,
	    		BlockType.BLOCKTYPE_INITIAL.name(), 0, 0);
	    genesisBlock.setTime(1532896109L); 
	    Transaction coinbase = new Transaction(params);
	    final ScriptBuilder inputBuilder = new ScriptBuilder();
	    inputBuilder.data(params.getChainId().getBytes(StandardCharsets.UTF_8));
	    coinbase.addInput(  TransactionInput.fromScriptBytes(params, coinbase, inputBuilder.build().getProgram())); 
	    RewardInfo rewardInfo = new RewardInfo(Sha256Hash.ZERO_HASH,
	            new HashSet<>(), 0L);
	
	    coinbase.setData(rewardInfo.toByteArray());
	    if (distribution != null && !distribution.isEmpty()) {
	        for (GenesisOutput out : distribution)
	            addOutput(params, out, coinbase);
	    } else if (params.genesisMintsBIG()) {
	        add(params, NetworkParameters.BigtangleCoinTotal, params.genesisPub, coinbase);
	    }
	    genesisBlock.addTransaction(coinbase);
	    genesisBlock.setHeight(0);
	    return genesisBlock;
	}

    private static void addOutput(NetworkParameters params, GenesisOutput out, Transaction coinbase) {
	    Coin base = new Coin(out.amount, NetworkParameters.BIGTANGLE_TOKENID);
	    Script script;
	    if (out.pubkeyHex != null) {
	        byte[] pubBytes = Utils.HEX.decode(out.pubkeyHex.trim());
	        PQKey key;
	        // Legacy EC pubkeys (0x02/0x03/0x04 prefix) - wrap in KeyBundle
	        if (pubBytes.length > 0 && (pubBytes[0] == 0x02 || pubBytes[0] == 0x03 || pubBytes[0] == 0x04)) {
	            List<net.bigtangle.crypto.pq.KeyBundle.Entry> entries = new ArrayList<>();
	            entries.add(new net.bigtangle.crypto.pq.KeyBundle.Entry(
	                net.bigtangle.crypto.pq.PQConstants.ALG_ML_DSA_87, pubBytes));
	            net.bigtangle.crypto.pq.KeyBundle bundle = new net.bigtangle.crypto.pq.KeyBundle(entries);
	            key = PQKey.fromPublicOnly(bundle);
	        } else {
	            key = PQKey.fromPublicOnly(pubBytes);
	        }
	        script = ScriptBuilder.createOutputScript(key);
	    } else {
	        script = ScriptBuilder.createOutputScript(Address.fromBase58(params, out.address));
	    }
	    coinbase.addOutput(new TransactionOutput(params, coinbase, base, script.getProgram()));
	}

	/**
	 * Parses a GenesisOutput CSV into a distribution list. The CSV has a header
	 * row and columns {@code address,pubkey,value} — the {@code pubkey} column
	 * may be empty for address-only rows. Row order is preserved so the genesis
	 * hash is deterministic for a given file.
	 */
	public static List<GenesisOutput> loadGenesisOutputsFromCsv(String path) {
	    List<GenesisOutput> outputs = new ArrayList<>();
	    try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
	        String line;
	        boolean header = true;
	        while ((line = reader.readLine()) != null) {
	            line = line.trim();
	            if (line.isEmpty())
	                continue;
	            if (header) {
	                header = false;
	                continue;
	            }
	            String[] cols = line.split(",", -1);
	            String address = cols.length > 0 ? cols[0].trim() : "";
	            String pubkey = cols.length > 1 ? cols[1].trim() : "";
	            BigInteger value = cols.length > 2 ? new BigInteger(cols[2].trim()) : BigInteger.ZERO;
	            if (!pubkey.isEmpty())
	                outputs.add(GenesisOutput.toPubkey(value, pubkey));
	            else
	                outputs.add(GenesisOutput.toAddress(value, address));
	        }
	    } catch (IOException e) {
	        throw new RuntimeException("Failed to load genesis distribution CSV: " + path, e);
	    }
	    return outputs;
	}

}
