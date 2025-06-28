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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.spongycastle.crypto.digests.RIPEMD160Digest;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Resources;
import com.google.common.primitives.Ints;
import com.google.common.primitives.UnsignedLongs;

import net.bigtangle.exception.AddressFormatException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.utils.Base58;

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
		    Block genesisBlock = new Block(params, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH,
		    		BlockType.BLOCKTYPE_INITIAL.ordinal(), 0, 0, Utils.encodeCompactBits(params.getMaxTarget()));
		    genesisBlock.setTime(1532896109L); 
		    genesisBlock.setDifficultyTarget(Utils.encodeCompactBits(params.getMaxTarget())); 
		    Transaction coinbase = new Transaction(params);
		    final ScriptBuilder inputBuilder = new ScriptBuilder();
		    coinbase.addInput(new TransactionInput(params, coinbase, inputBuilder.build().getProgram())); 
		    RewardInfo rewardInfo = new RewardInfo(Sha256Hash.ZERO_HASH,
		            Utils.encodeCompactBits(params.getMaxTargetReward()),
		            new HashSet<>(), 0L);
		
		    coinbase.setData(rewardInfo.toByteArray());
		    Utils.add(params, NetworkParameters.BigtangleCoinTotal, params.genesisPub, coinbase);
		    genesisBlock.addTransaction(coinbase);
		    genesisBlock.setNonce(0);
		    genesisBlock.setHeight(0);
		    return genesisBlock;
		
		}

}
