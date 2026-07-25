/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2013 Google Inc.
 * Copyright 2015 Andreas Schildbach
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
package net.bigtangle.params;


import com.google.common.collect.ImmutableList;

/**
 * Parameters for the main production network on which people trade goods and
 * services.
 */
public class MainNetParams extends NetworkParameters {
 

    public MainNetParams() {
        super();
        difficultyLimitCompact = 508814037L;
        rewardDifficultyLimitCompact = 503371455L;

        addressHeader = 0;
        p2shHeader = 5;
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };

        packetMagic = 0xf9beb4d9L;
        bip32HeaderPub = 0x0488B21E; // The 4 byte header that serializes in
                                     // base58 to "xpub".
        bip32HeaderPriv = 0x0488ADE4; // The 4 byte header that serializes in
                                      // base58 to "xprv"

        genesisPub = "03d6053241c5abca6621c238922e7473977320ef310be0a8538cc2df7ee5a0187c";

        permissionDomainname = ImmutableList.of("0222c35110844bf00afd9b7f08788d79ef6edc0dce19be6182b44e07501e637a58");
     
       
        id = ID_MAINNET;
      
        spendableCoinbaseDepth = 100;

        dnsSeeds = new String[] { "enrtree://0000000000000000000000000000000000000000000000000000000000000000@seeds.bigtangle.org" };
       

    }

    public String[] serverSeeds() {
        return new String[] { "92.5.34.128:80", "43.132.208.9:80", "43.162.118.46:80" };

    }

    private static MainNetParams instance;

    public static synchronized MainNetParams get() {
        if (instance == null) {
            instance = new MainNetParams();
        }
        return instance;
    }


}
