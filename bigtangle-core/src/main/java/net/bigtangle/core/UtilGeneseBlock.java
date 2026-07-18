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
	
	 
	    public static void add(NetworkParameters params, BigInteger amount, String account, Transaction coinbase) {
	        // amount, many public keys
	        String[] list = account.split(",");
	        Coin base = new Coin(amount,NetworkParameters. BIGTANGLE_TOKENID);
	        List<ECKey> keys = new ArrayList<>();
	        for (String s : list) {
	            keys.add(ECKey.fromPublicOnly(Utils.HEX.decode(s.trim())));
	        }
	        if (keys.size() <= 1) {
	            coinbase.addOutput(new TransactionOutput(params, coinbase, base,
	                    ScriptBuilder.createOutputScript(ECKey.fromPublicOnly(keys.get(0).getPubKey())).getProgram()));
	        } else {
	            Script scriptPubKey = ScriptBuilder.createMultiSigOutputScript(keys.size() - 1, keys);
	            coinbase.addOutput(new TransactionOutput(params, coinbase, base, scriptPubKey.getProgram()));
	        }
	    }

		public static Block createGenesis(NetworkParameters params) {
		    Block genesisBlock =   Block.setBlock7(params, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH,
		    		BlockType.BLOCKTYPE_INITIAL.ordinal(), 0, 0);
		    genesisBlock.setTime(1532896109L); 
		    Transaction coinbase = new Transaction(params);
		    final ScriptBuilder inputBuilder = new ScriptBuilder();
		    inputBuilder.data(params.getChainId().getBytes(StandardCharsets.UTF_8));
		    coinbase.addInput(  TransactionInput.fromScriptBytes(params, coinbase, inputBuilder.build().getProgram())); 
		    RewardInfo rewardInfo = new RewardInfo(Sha256Hash.ZERO_HASH,
		            Utils.encodeCompactBits(params.getMaxTargetReward()),
		            new HashSet<>(), 0L);
		
		    coinbase.setData(rewardInfo.toByteArray());
	    if (params.genesisMintsBIG()) {
	        add(params, NetworkParameters.BigtangleCoinTotal, params.genesisPub, coinbase);
	    }
	    genesisBlock.addTransaction(coinbase);
		    genesisBlock.setHeight(0);
		    return genesisBlock;
		
		}

}
