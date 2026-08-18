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

import net.bigtangle.crypto.pq.PQConstants;

/**
 * Parameters for the main production network on which people trade goods and
 * services.
 */
public class MainNetParams extends NetworkParameters {
 

    public MainNetParams() {
        super();
        addressHeader = 0;
        p2shHeader = 5;
        dumpedPrivateKeyHeader = 128;
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };

        packetMagic = 0xf9beb4d9L;
        bip32HeaderPub = 0x0488B21E; // The 4 byte header that serializes in
                                     // base58 to "xpub".
        bip32HeaderPriv = 0x0488ADE4; // The 4 byte header that serializes in
                                      // base58 to "xprv"

        genesisPub = "03d6053241c5abca6621c238922e7473977320ef310be0a8538cc2df7ee5a0187c";

        // Root-domain signer for token creation. MUST be a valid PQ pubkey
        // (050101010a prefix) whose private key is held by the domain issuer;
        // an EC key/hash here makes PermissionDomainname.getOutKey() fall back
        // to a random key every call and token creation can never be signed.
        permissionDomainname = ImmutableList.of(
                "050101010a2012db222fcf7b2fe15889f5251951b694d282751020f506492109caba7e8a018fe50479cd2daca70c7d13b61d973f989e762e6f822c7ab05c0109d5065180b27d3ac859c6d4ce26601c4f5dcf7ff088370dd55e7ae395da673fa1785b5353670bda98f8d95da9b7e33f84b59668dc2a6ef2b0285cbccedb3ca646178c324ae8c736ad5789bf35b3903cd9a7845f5d3b87f70c26354b1e10485f903270249dc408d24ce460a90535f25d87ddd3d3b8a287a810d5223712a77ca421d3f8ca6fbd7efdc434460d24955ed6d54a12ea75aab381fcaf66b4f0cd2363e1306fad043ebc5dd736703b1415d20f6cc8921802f7522610fa456644073bb9c6545b69a4f12e30014c6100b8677de57dc78b522d8c50cd9400d8d6caa9425abeffef5698e2fb82520e31eb260957bea06b65e18ce1c55a50b24c92e56762b1d6e85e2c44918c174417e5c1490ceb0b52f143a9317fa7b71d0c069053dc197d40c4db936f66af9e0e096ad7a3a11d76106f80bccbf9d48a1af443cf29e50bd379ae734e817a682583c0951f9f4b96ec86c856377b7e89832bd510f1f83f8018f3e36c552345fa5bb96b01b4e4a850511bf5ebf7d01e15b8e7cd1591a88aec8b7f00a81488935bc8275283d69b3f75cebc49c125940c5af0c2f99758581b4072eb7c76495d6352954e2ef9c77429d664ecbe1782682d98c2b0b907881ebcce38613a1474e8967e7f9ea4b2e60ecc3a0a17d4fdbdd7633146812c1a4cc506c5f49e9e28b2ac0d2561af51ef2d60750d43753e7995017d3b6d679e8302e80fb57fd9a17861c53b810ab902f640a121f43c7534aedde5e5f8ba1f5508dc3d20acfd8bfdbe484b1679a24708d1a25f90bb34f9902498c9177736d4a102dea77d406cefd3c5c9bd6ac22ab5b3e7826458ce5e598d4a984269815d1b3e966df76dfee3244199babdef0c6e913c9408e288a14a164a414429810bbf89682dc9269717f983ae99f0fbc18c8d5bfbbb45d44d4de846029939a8798b5e14d2076d4e96219bcae4454e4c18efe0d9db53eb8b1d9433bdd1f5562651a645145b0fe5280fb9ddd571738e17e985abebc14139e090935fa768f781843bc912c5419e09b35a3a8fb1a97b8e8054a391875aca59373ce1ca75932f3ecc8d335ebc72d13ef8e4a84fc5a7b24bfe5b181620e26951ec40a8bd496a5de076ee988d7592a398af315cdc74dd77933b15da3842814acfb123b11c3bdb52ed888468cba660a7d0c96d6a898e9ffc518e160b47d55697d1c6d70a7f667686e3cc7cf29bc1a649374fce962a55ab0ae3603a30445ac92390847fcce1cbfced92a5bbe62b008ca6e2c34df03dc0c36d25a260bf1a813655cd299078e69eff3bf281353943b06c3c367f9488078e3de3a19cd0295f2b892267e92c8e6ed784b133e3423ab76c1d2af596935b8b88749e9854d7540e0742e939bfbf41582aed5141ecc3d77bbc47a68f328e2e7ba76cc655bcebaab06839f6922b205981379c0c0ddd78794ba5914274acbff3e9d0152072c1b35402e5650320ae242b039611ff3aa6b1784f0c12d9ec1c2b51274a3ca9e5ff708d97073d1a4e88e35e645835817703e2dba9340542a372eff76e310344158ca249da461fccbb7aee881bb4403449df56725529e46c0479f1a43ae1f6c5ad3e27767d43426880f86aba18571a1e3b300910a5daeb41f10347b138986e285bb9717baf50aaf9fb4aa629a0e7294c2b718bbdeaa1d48449b98e66ee998ed8b3f7169843e19c1cd9eda97cf493e992d140775a188ee0912b923ffe5921612a2d2254e67d9a209c9f8407d0e950b61e47ab75c681fdb5e69b488ec68eb76af484e8ab5611a3bde37f0bc6cf1c04526dcf897818aa22073f2cac674250656ccd0fa2cc8deb9e57e6b53b5757b8391038a235a76290201358a83909aed1afc9d04d9493824e66d68568ddbab7c3ea14ec1201dc899603f9090746a9375323a25e0ec2274ae53562c0f2e438db723526fca2f1871259a9b209bb6aadb8ddb0fd49de7493eaca386d47682062e7a82b626e4d9ffc290a0aff9dbda6ff9a756c56111ce8c4de6150eb352c887b7a1e70fa3477a237a468ec403f10e8e7f2f97bee5d022b2968dcee1bb7f0a32c35889302ceafa4ffceb8c46dff95f69c23cc0e43668e76ad221e5ab0ea70fa1d84665ceba78422aeb0ed51a3a402cb99d802375ea7c444d4dde141db59915154615338f3af70d925fbab42eb1176c2b860bf6a9220f2b140694e8cfc3aa5735ae23dc13bc7334bf71d5ed1b3579b9bac55e34d58c56dd6bdc46111b67927fce501104dc5e1e1f8557d2395a16fb854eebb63415d65fc494934295aceaf808dc032d983568645c00d97ed708bd87d450f6b647c0172da1a57864d089ad3b1de3b1734abb32ee892a0f6b9aea99464c01fdf50368240233d9f9cdf9af26c7d14c672da4aa5d44efcf77da0664890a89b9adce7cb466358cb74d488487bcd8f591b75ca89e3299ee1202bbd79a134432b215a2706c27b794170f093a35d09e0c0c784aa6c74a9fe712f3f76dc810b81617caf5a92a2b57d85b602d5bad091bcb78b877dacbbc10ad2133970111d8b9702902579061fca2670099afaa00c604b5d1d5cc7c15f6d2fb8fbfc990501d564140f6537b41c4ef55db6b04c3b3ec72aefcd92fa28575849a71fc0facb75f5360513bd3619829cf1b0eae9d49db8f5789a5da4ebeaca4893324d74ecd7b245f33e102b8ba55c710b0e33eafc9d95f289663718bc93a2eada3cac08f6028eedb0508943e0a12b53147534d2cea522a320f8da6be34ab31ed2094df436353d9d0f8880029184263407c47178c40f672140fa3e37001471c77b652b434d777558c8c4c04184a271bb1a808faec86242ef905aa897e672def73ad2399542517ba1def1cd50855b919781c82d13749bdadd3c807ed8c2fc85e8a1a823ed96ab637fe441d12407185a7a1c0bde2f61fce0c5579fc57b56ee1d32ae8de7ea85949287a8f91b6feb394c47fe12029bfe7cf002b159ca26b43f99ada9d359ddfb24fe9f08c759de68d9e2fee16374d93d5a80c7368c9fd1bc5b4e9659d291539d301001a0f04cffb592f471c64fcbebea40990529424575d3d2c948430be59a04820c74e745838f1533340a98c8b7ff805550f34a5ddf8585d5d9f36f2a4eab5334e890fe3a3e02810651d18acac278be2cf492e0d0b9b3b4ee05c7b900225a447db3a3f55fe11571bd9effecfcda97d07b6e71ef3661d8d13c9d3ce4f894ca953ff61fdecb505f20d8eae13c39451d3a68bbe82eacaafb561f78a7b244043c1a0692bf6919919716d22ac26032d0f160e7a436e891146647f4a74625927857d02ddf8c3a5888864b01b696739f61f479097bc70f4cf02e01982a66867808b2bc61d589b3f25507bf094006b5d13f1ab03358c7004b8a566da731921e0a63f7ae1e86f5515b28dd90c00c965933a29210b7661b0b670886763f5f8f230e561faae5c9c808b9f271d3b490860bfb5a07664aabc92f5bbafe7a7b7f558998ed50641484f465a1f74f58307dd7849de768e252898f8c55c2f3edabc0e32e8ce91317c6834fe7cf3890077d1017e5cadccb3e99f06af549b7dfe1a362de23892843f4f27728f15af4ef6a7787a165cc2a78");

        // PQ governance: ML-DSA-87 from genesis; dual suite after the
        // configured chain length (default: never). Mainnet genesis is legacy
        // EC; the suite table only gates the post-quantum proposer path.
        setPqSuiteActivationHeight(PQConstants.SUITE_ML_DSA_ONLY, 0);
        long dualActivation = PQConstants.dualActivationHeightFromProperty();
        if (dualActivation >= 0) {
            setPqSuiteActivationHeight(PQConstants.SUITE_CAT5_DUAL_1, dualActivation);
        }
     
       
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
