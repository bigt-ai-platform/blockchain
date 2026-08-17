package net.bigtangle.mcmc.prodsim;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.bigtangle.core.PQKey;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.params.TestParams;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

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
        byte[] mlDsaSeed = new byte[32];
        Arrays.fill(mlDsaSeed, (byte) 0x01);

        List<PQKey> validatorKeys = new ArrayList<>();
        for (String hex : VALIDATOR_KEY_HEX) {
            byte[] seed = Utils.HEX.decode(hex);
            validatorKeys.add(PQKey.fromSeeds(
                    Arrays.copyOfRange(seed, 0, 32),
                    Arrays.copyOfRange(seed, 32, 64)));
        }

        // Each of the 4 prodsim nodes runs its OWN database and its OWN MCMC
        // node with its OWN configured validator key (pos.validatorKey). The
        // server signs STAKE deposits with that configured key and rejects any
        // other pubkey, so each node stakes its own validator. The deposit is
        // chain-derived: the STAKE block is saved and the validator set is
        // derived from it.
        int basePort = 8081 + Integer.getInteger("prodsim.portOffset", 20000);
        for (int node = 0; node < 4; node++) {
            String nodeUrl = "http://localhost:" + (basePort + node) + "/";
            System.out.println("=== Bootstrapping node " + nodeUrl + " ===");

            PQKey validatorKey = validatorKeys.get(node);
            String pubkeyHex = Utils.HEX.encode(validatorKey.getPubKey());
            System.out.println("  VALIDATOR_PUBKEY_" + node + "=" + pubkeyHex);

            // Validators are funded IN THE GENESIS distribution (the shared
            // GenesisOutput.csv), so the funding UTXOs exist identically on
            // every node and the stake blocks apply cross-node. fundAddresses
            // inserts only local UTXOs that never propagate — with it the 4
            // nodes can never converge on a shared active set.
            long balance = waitForBalance(nodeUrl, validatorKey.getPubKey(), STAKE_AMOUNT.longValue());
            System.out.println("  Validator " + node + " confirmed balance=" + balance);
            if (balance < STAKE_AMOUNT.longValue()) {
                throw new IllegalStateException(
                        "Validator " + node + " not funded in genesis (balance=" + balance + ")");
            }

            HashMap<String, Object> stakeReq = new HashMap<>();
            stakeReq.put("pubkey", pubkeyHex);
            stakeReq.put("amount", STAKE_AMOUNT.toString());
            boolean staked = false;
            for (int attempt = 0; attempt < 3 && !staked; attempt++) {
                try {
                    OkHttp3Util.postString(nodeUrl + ReqCmd.stakeDeposit.name(),
                            Json.jsonmapper().writeValueAsString(stakeReq));
                    System.out.println("  Staked validator " + node);
                    staked = true;
                } catch (Exception e) {
                    System.out.println("  stakeDeposit attempt " + attempt + " failed: " + e.getMessage());
                    Thread.sleep(3000);
                }
            }
            if (!staked) {
                throw new IllegalStateException(
                        "Validator " + node + " could not stake after 3 attempts on " + nodeUrl);
            }
            Thread.sleep(1000);

            HashMap<String, Object> activateReq = new HashMap<>();
            activateReq.put("pubkey", pubkeyHex);
            activateReq.put("epoch", 0L);
            boolean activated = false;
            for (int attempt = 0; attempt < 3 && !activated; attempt++) {
                try {
                    OkHttp3Util.postString(nodeUrl + ReqCmd.activateValidator.name(),
                            Json.jsonmapper().writeValueAsString(activateReq));
                    System.out.println("  Activated validator " + node);
                    activated = true;
                } catch (Exception e) {
                    System.out.println("  activateValidator attempt " + attempt + " failed: " + e.getMessage());
                    Thread.sleep(3000);
                }
            }
            if (!activated) {
                throw new IllegalStateException(
                        "Validator " + node + " could not be activated after 3 attempts on " + nodeUrl);
            }
            Thread.sleep(3000);

            byte[] resp = OkHttp3Util.postString(nodeUrl + ReqCmd.getValidators.name(), "{}");
            Map<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
            Object validatorsObj = result.get("validators");
            if (validatorsObj == null && result.get("text") instanceof String) {
                result = Json.jsonmapper().readValue((String) result.get("text"), HashMap.class);
                validatorsObj = result.get("validators");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> validators = (List<Map<String, Object>>) validatorsObj;
            System.out.println("Active validators on " + nodeUrl + ": "
                    + (validators != null ? validators.size() : 0));
            if (validators == null || validators.size() < 1) {
                System.out.println("WARNING: Expected at least 1 validator on " + nodeUrl);
            }
        }

        // Wait for the full active set to converge on EVERY node before beacon
        // production ramps up. The stake blocks gossip to all nodes and apply at
        // connect time, but if the sim starts while the sets are still asymmetric
        // (node 0 sees 1, node 3 sees 4), each node picks a different proposer
        // for the same slots and forks the beacon chain irrecoverably.
        System.out.println("Waiting for validator-set convergence on all nodes...");
        int maxWaitSec = Integer.getInteger("prodsim.convergeWaitSec", 120);
        long deadline = System.currentTimeMillis() + maxWaitSec * 1000L;
        boolean converged = false;
        while (System.currentTimeMillis() < deadline) {
            int minActive = Integer.MAX_VALUE;
            for (int node = 0; node < 4; node++) {
                int count = activeValidatorCount("http://localhost:" + (basePort + node) + "/");
                minActive = Math.min(minActive, count);
            }
            System.out.println("  min active validators across nodes: " + minActive);
            if (minActive >= 4) {
                converged = true;
                break;
            }
            Thread.sleep(5000);
        }
        if (!converged) {
            throw new IllegalStateException(
                    "Validator set did not converge to 4 on all nodes within " + maxWaitSec + "s");
        }
        System.out.println("Bootstrap complete. Each node staked its own validator; active set converged.");
    }

    private static int activeValidatorCount(String nodeUrl) {
        try {
            byte[] resp = OkHttp3Util.postString(nodeUrl + ReqCmd.getValidators.name(), "{}");
            Map<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
            Object validatorsObj = result.get("validators");
            if (validatorsObj == null && result.get("text") instanceof String) {
                result = Json.jsonmapper().readValue((String) result.get("text"), HashMap.class);
                validatorsObj = result.get("validators");
            }
            if (validatorsObj instanceof List) {
                return ((List<?>) validatorsObj).size();
            }
        } catch (Exception e) {
            // transient — keep polling
        }
        return 0;
    }

    /**
     * Fund a single validator via the fundAddresses endpoint. This directly
     * inserts a confirmed BIG UTXO for the validator pubkey on the node, so
     * staking does not depend on a beacon confirming the funding.
     */
    private static void fundValidator(String serverUrl, PQKey beneficiary, BigInteger amountPer) throws Exception {
        String pubkeyHex = Utils.HEX.encode(beneficiary.getPubKey());
        Map<String, Object> entry = new HashMap<>();
        entry.put("address", "validator");
        entry.put("value", amountPer.longValue());
        entry.put("pubkey", pubkeyHex);
        List<Map<String, Object>> addresses = new ArrayList<>();
        addresses.add(entry);
        Map<String, Object> req = new HashMap<>();
        req.put("addresses", addresses);
        OkHttp3Util.postString(serverUrl + "fundAddresses",
                Json.jsonmapper().writeValueAsString(req));
        System.out.println("  Funded validator");
    }

    /**
     * Poll getBalances until the validator's confirmed BIG balance reaches
     * {@code min}. stakeDeposit rejects unconfirmed funding UTXOs (404), so the
     * bootstrap must wait for confirmation before staking.
     */
    private static long waitForBalance(String serverUrl, byte[] pubkey, long min) throws Exception {
        List<String> keyHex = new ArrayList<>();
        keyHex.add(Utils.HEX.encode(Utils.sha256hash160(pubkey)));
        long balance = 0;
        for (int i = 0; i < 40; i++) {
            try {
                byte[] resp = OkHttp3Util.post(serverUrl + ReqCmd.getBalances.name(),
                        Json.jsonmapper().writeValueAsString(keyHex).getBytes());
                GetBalancesResponse r = Json.jsonmapper().readValue(resp, GetBalancesResponse.class);
                balance = 0;
                for (UTXO u : r.getOutputs()) {
                    if (u.getValue().getValue().signum() > 0
                            && Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, u.getTokenidBuf())) {
                        balance += u.getValue().getValue().longValue();
                    }
                }
                if (balance >= min) return balance;
            } catch (Exception e) {
                // node not ready / transient — keep polling
            }
            Thread.sleep(3000);
        }
        return balance;
    }
}
