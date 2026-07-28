package net.bigtangle.mcmc.prodsim;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.params.TestParams;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

public class ProdSimBootstrap {

    private static final String[] VALIDATOR_KEY_HEX = {
        "821d95e8b04cd2b6edcf663881734915501e92d5d377308b522a5a6725243870404fee8dc9d8bdc015386e353d5206be982bb67c4bb4f7f5fb1afb0ac7dea38b",
        "e2df872ffa069716425675cc2732e41a6bce2831c274ab9799fee43282252aac09f204dcb3e9066baf405995ee7c8701cc91facafdc22346523057981d8d3cd2",
        "6fe520f3d3f3a2e4b2442198d9a5535bd4a0f67a51055e7c77735bb06264acb565dd64217603f279cc60507afd7a06520deed1a450d3694d676f942c927cbc75",
        "9ae978305852ab8b2406ab56d4cdc50fb7c9c8e62b17d97515c18c69eb0e4722d773d36ad1fa9b1c38a132c389fd4d4dcdf8eb40422da76322a90e34d200103e"
    };

    private static final BigInteger STAKE_AMOUNT = BigInteger.valueOf(32_000_000L);
    private static final BigInteger FUND_PER_VALIDATOR = STAKE_AMOUNT.multiply(BigInteger.valueOf(5));

    public static void main(String[] args) throws Exception {
        String serverUrl = System.getProperty("server.url", "http://localhost:8081/");
        NetworkParameters params = TestParams.get();

        byte[] mlDsaSeed = new byte[32];
        byte[] slhDsaSeed = new byte[32];
        Arrays.fill(mlDsaSeed, (byte) 0x01);
        Arrays.fill(slhDsaSeed, (byte) 0x02);
        PQKey genesisKey = PQKey.fromSeeds(mlDsaSeed, slhDsaSeed);

        Wallet wallet = Wallet.fromKeys(params, genesisKey, serverUrl);

        for (int i = 0; i < VALIDATOR_KEY_HEX.length; i++) {
            byte[] seed = Utils.HEX.decode(VALIDATOR_KEY_HEX[i]);
            PQKey validatorKey = PQKey.fromSeeds(
                    Arrays.copyOfRange(seed, 0, 32),
                    Arrays.copyOfRange(seed, 32, 64));

            String pubkeyHex = Utils.HEX.encode(validatorKey.getPubKey());

            // Fund the validator using legacy address format
            net.bigtangle.core.Address legacyAddr = net.bigtangle.core.Address.fromHash160(
                    params, Utils.sha256hash160(validatorKey.getPubKey()));
            wallet.pay(null, legacyAddr.toBase58(),
                    new Coin(FUND_PER_VALIDATOR, NetworkParameters.BIGTANGLE_TOKENID),
                    "fund validator " + i);
            System.out.println("Funded validator " + i + " at " + legacyAddr.toBase58());
            Thread.sleep(12000);
        }

        System.out.println("All validators funded. Now staking...");

        for (int i = 0; i < VALIDATOR_KEY_HEX.length; i++) {
            byte[] seed = Utils.HEX.decode(VALIDATOR_KEY_HEX[i]);
            PQKey validatorKey = PQKey.fromSeeds(
                    Arrays.copyOfRange(seed, 0, 32),
                    Arrays.copyOfRange(seed, 32, 64));

            String pubkeyHex = Utils.HEX.encode(validatorKey.getPubKey());

            HashMap<String, Object> stakeReq = new HashMap<>();
            stakeReq.put("pubkey", pubkeyHex);
            stakeReq.put("amount", STAKE_AMOUNT.toString());
            try {
                byte[] resp = OkHttp3Util.postString(serverUrl + ReqCmd.stakeDeposit.name(),
                        Json.jsonmapper().writeValueAsString(stakeReq));
                System.out.println("  Staked validator " + i);
            } catch (Exception e) {
                System.out.println("  stakeDeposit failed for validator " + i + ": " + e.getMessage());
            }
            Thread.sleep(1000);
        }

        Thread.sleep(12000);

        for (int i = 0; i < VALIDATOR_KEY_HEX.length; i++) {
            byte[] seed = Utils.HEX.decode(VALIDATOR_KEY_HEX[i]);
            PQKey validatorKey = PQKey.fromSeeds(
                    Arrays.copyOfRange(seed, 0, 32),
                    Arrays.copyOfRange(seed, 32, 64));

            String pubkeyHex = Utils.HEX.encode(validatorKey.getPubKey());

            HashMap<String, Object> activateReq = new HashMap<>();
            activateReq.put("pubkey", pubkeyHex);
            activateReq.put("epoch", 0L);
            try {
                OkHttp3Util.postString(serverUrl + ReqCmd.activateValidator.name(),
                        Json.jsonmapper().writeValueAsString(activateReq));
                System.out.println("  Activated validator " + i);
            } catch (Exception e) {
                System.out.println("  activateValidator failed for validator " + i + ": " + e.getMessage());
            }
            Thread.sleep(500);
        }

        Thread.sleep(3000);
        byte[] resp = OkHttp3Util.postString(serverUrl + ReqCmd.getValidators.name(), "{}");
        Map<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> validators = (List<Map<String, Object>>) result.get("validators");
        System.out.println("Active validators: " + (validators != null ? validators.size() : 0));
        if (validators != null) {
            for (Map<String, Object> v : validators) {
                System.out.println("  Validator pubkey: " + v.get("pubkey"));
            }
        }
        if (validators == null || validators.size() < VALIDATOR_KEY_HEX.length) {
            System.out.println("WARNING: Not all validators activated.");
        } else {
            System.out.println("Bootstrap complete. All validators active.");
        }
    }
}
