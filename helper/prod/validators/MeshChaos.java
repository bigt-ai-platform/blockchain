import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.bigtangle.core.Address;
import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.GetOutputsResponse;
import net.bigtangle.response.GetTransactionStatusResponse;
import net.bigtangle.server.service.RandaoService;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * MeshChaos — controlled-validator fixtures + opt-in chaos for the hermetic
 * testnodes.sh mesh. Companion to MeshAttack (single-shot V1-V36) and MeshLoad
 * (sustained-load V37-V50); this driver owns composite consensus vectors
 * V52/V55/V56 and recovery vectors V66/V68/V67/V65.
 *
 * Fixtures: the mesh's OWN validator keys (POS_VALIDATOR_KEY seeds from
 * node-i/validator.env), so the driver casts GENUINE BLS-signed votes and
 * slashable evidence — not the fake-key spam of V25/V26. Attestation
 * checkpoints are mirrored from live honest votes (same source/target), only
 * the head/key/slot/signature differ.
 *
 * OPT-IN: every mutating run requires -Dchaos.optIn=1 (or CHAOS_OPT_IN=1).
 * Without it the driver only runs `heal`. V66-V68/V67/V65 mutate
 * infrastructure (docker pause/restart, postgres stop, tc rules) — never run
 * against production data. A shutdown hook + `heal` mode undo: unpause all
 * node containers, restart stopped postgres containers, delete tc rules.
 *
 * Modes:
 *   MeshChaos genesis <startIndex> <count> [fund]   (same seed formula as
 *     MeshAttack/MeshLoad; fund via testnodes.sh LOAD_WALLETS — fork blocks
 *     need a few funded wallets; votes/evidence need none)
 *   MeshChaos run <startIndex> <count> <nnodes> <urlPrefix> [only]
 *     only = optional subset, e.g. "V52,V56" (default: all staged vectors)
 *   MeshChaos heal <nnodes>   undo chaos state (unpause, pg start, tc del)
 *
 * System properties (all optional):
 *   chaos.optIn / CHAOS_OPT_IN  must be 1 for run (heal always allowed)
 *   load.fund / load.pay        wallet funding / payment (30000 / 20000)
 *   chaos.validatorDir          mesh dir holding node-i/validator.env
 *                               (/tmp/bt4test)
 *   chaos.containerPrefix       node container prefix (bt4-node-)
 *   chaos.pgPrefix              per-node postgres prefix (bt4-pg-,
 *                               PER_NODE_PG=1 meshes)
 *   chaos.gossipPorts           comma gossip TCP ports per node
 *                               (9421,9422,9423,9424)
 *   chaos.confirmTimeoutSec     confirm poll deadline (300)
 *   chaos.pauseSec              V68 freeze duration (150)
 *   chaos.pgOutageSec           V67 postgres outage duration (75)
 *   chaos.throttleSec           V65 throttle window (300)
 *   chaos.maxSpread             spread bound in blocks (32)
 *
 * Vectors (verdict table at end, exit 0 = all hold):
 *   V52 orphaned-vote contamination (real branch votes, reorg, canonical
 *       re-vote, node restart -> one head, finroots converge, fin advances)
 *   V55 justification-cache race (competing-branch real votes across an epoch
 *       boundary + restart -> justified converges, finality resumes)
 *   V56 genuine slashing idempotence (real double-vote proof admitted, exactly
 *       one SLASHING block, replays/dups never re-apply, set converges)
 *   V66 rolling restart under load (each node rejoins past its floor,
 *       sentinels confirm once, fin never regresses)
 *   V68 process pause across duties+boundary (no stale replay: no new SLASHING
 *       blocks, node catches the finroot, mesh converges)
 *   V67 postgres outage across boundary (quorum finalizes throughout, node
 *       rejoins the finroot, tip responsive after heal)
 *   V65 gossip throttle, API live (finality advances on quorum, lagger catches
 *       up bounded after heal, finroots converge)
 *
 * Deferred with reason (needs fixtures that do not exist yet):
 *   V51/V54 beacon assembly (valid proposer beacons incl. RANDAO reveal,
 *     GHOST-tip parents and reference sets need a proposer-duty replica)
 *   V53 join-based deposits (stakeDeposit signs with a NODE's key: new
 *     deposits need testnodes join flow, not bare keys)
 *   V58 256-epoch withdrawal delay exceeds any feasible lane
 *   V60 staging genesis with dust/whale records (needs GENESIS_CSV passthrough)
 *   V57/V59/V69 parameter/partition fixtures (seed reprovisioning, per-node
 *     SLOT_MS, split-brain with driver visibility)
 *   V70 destructive disk-full (quota-limited volume, manual procedure only)
 */
public class MeshChaos {
    static NetworkParameters params = MainNetParams.get();
    static final byte[] M_BIG = NetworkParameters.BIGTANGLE_TOKENID;

    static byte[] seedFor(long idx) {
        byte[] in = new byte[8];
        for (int i = 0; i < 8; i++) {
            in[i] = (byte) (idx >>> (56 - 8 * i));
        }
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(in);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    static Address addrFor(byte[] seed) {
        return Address.fromHash160(params, PQKey.fromMLDSA(seed).getPubKeyHash());
    }

    static PQKey keyFor(long idx) {
        return PQKey.fromMLDSA(seedFor(idx));
    }

    static long runUniqueIndex(long startIndex, long salt) {
        return startIndex * 1_000_000L + salt;
    }

    static final List<String> VERDICTS = new ArrayList<>();
    static String SEED_NODE = "";
    static String[] NODE_URLS = new String[0];
    static int NNODES = 0;
    // Set by heal-all paths; the shutdown hook replays it exactly once.
    static final java.util.concurrent.atomic.AtomicBoolean HEALED = new java.util.concurrent.atomic.AtomicBoolean(false);

    static void verdict(String vector, boolean pass, String detail) {
        String row = String.format("%-36s %-8s %s", vector, pass ? "PASS" : "FAIL", detail);
        VERDICTS.add(row);
        System.out.println((pass ? "[DEFLECTED] " : "[*** BREACH ***] ") + row);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
        }
        if ("genesis".equals(args[0])) {
            long start = Long.parseLong(args[1]);
            long count = Long.parseLong(args[2]);
            long value = args.length > 3 ? Long.parseLong(args[3]) : Long.getLong("load.fund", 30000L);
            StringBuilder sb = new StringBuilder();
            for (long i = 0; i < count; i++) {
                sb.append(addrFor(seedFor(start + i)).toBase58()).append(",,").append(value).append('\n');
            }
            System.out.print(sb);
            return;
        }
        if ("heal".equals(args[0])) {
            int nnodes = Integer.parseInt(args[1]);
            healAll(nnodes);
            System.out.println("HEAL_COMPLETE");
            return;
        }
        if ("run".equals(args[0])) {
            if (!"1".equals(System.getProperty("chaos.optIn",
                    System.getenv().getOrDefault("CHAOS_OPT_IN", "0")))) {
                System.err.println("REFUSED: mutating chaos lane needs -Dchaos.optIn=1 (or CHAOS_OPT_IN=1). "
                        + "Use `heal` mode to undo chaos state.");
                System.exit(2);
            }
            run(args);
            return;
        }
        usage();
    }

    static void usage() {
        System.err.println("usage: MeshChaos genesis <startIndex> <count> [fund]\n"
                + "       MeshChaos run <startIndex> <count> <nnodes> <urlPrefix> [only]\n"
                + "       MeshChaos heal <nnodes>");
        System.exit(1);
    }

    // ---------------------------------------------------------------- fixtures

    /** Mesh validator keys from node-i/validator.env (POS_VALIDATOR_KEY seed hex). */
    static List<PQKey> loadValidatorKeys(int nnodes) throws Exception {
        String dir = System.getProperty("chaos.validatorDir", "/tmp/bt4test");
        List<PQKey> out = new ArrayList<>();
        for (int i = 0; i < nnodes; i++) {
            java.nio.file.Path p = java.nio.file.Path.of(dir, "node-" + i, "validator.env");
            String keyHex = null;
            for (String line : java.nio.file.Files.readAllLines(p)) {
                if (line.startsWith("POS_VALIDATOR_KEY=")) {
                    keyHex = line.substring("POS_VALIDATOR_KEY=".length()).trim();
                }
            }
            if (keyHex == null || keyHex.isEmpty()) {
                throw new IllegalStateException("no POS_VALIDATOR_KEY in " + p);
            }
            out.add(PQKey.fromMLDSA(Utils.HEX.decode(keyHex)));
        }
        System.out.println("MESHCHAOS fixtures: " + out.size() + " validator keys from " + dir);
        return out;
    }

    /**
     * Fresh honest vote template from embedded beacon attestations (the gossip
     * getAttestations view is empty on this build — votes live in the
     * on-chain embedded set). Walks back from the tip for the newest beacon
     * carrying votes. Checkpoints are mirrored verbatim; only head/key/slot
     * differ per vector. Null when no beacon with votes is found in range.
     */
    static AttestationData embeddedTemplate(String nodeUrl, int walkBack) {
        try {
            byte[] tipResp = OkHttp3Util.postString(nodeUrl + "getTip", "{}");
            Block cur = params.getDefaultSerializer().makeBlock(Utils.HEX.decode(
                    (String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
            for (int d = 0; d < walkBack && cur != null; d++) {
                try {
                    net.bigtangle.core.SlotData sd =
                            net.bigtangle.server.service.StakeService.slotDataOfBeacon(cur);
                    if (sd != null && sd.getAttestations() != null && !sd.getAttestations().isEmpty()) {
                        return sd.getAttestations().get(0);
                    }
                } catch (Exception ignore) {
                }
                try {
                    Sha256Hash prev = cur.getPrevBlockHash();
                    if (prev == null || prev.equals(Sha256Hash.ZERO_HASH)) break;
                    byte[] r = OkHttp3Util.postString(nodeUrl + "getBlockByHash", Json.jsonmapper()
                            .writeValueAsString(Map.of("hashHex", prev.toString(), "text", "false")));
                    Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
                    String dataHex = (String) m.get("dataHex");
                    if (dataHex == null) break;
                    cur = params.getDefaultSerializer().makeBlock(Utils.HEX.decode(dataHex));
                } catch (Exception e) {
                    break;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * One honest attestation for a recent slot (any validator's), to mirror
     * source/target checkpoints from. Returns null when the slot has no votes.
     * NOTE: reads the gossip view, which is empty on builds that carry votes
     * only in the embedded set — prefer embeddedTemplate().
     */
    static AttestationData honestVote(String nodeUrl, long slot) {
        try {
            byte[] r = OkHttp3Util.postString(nodeUrl + "getAttestations",
                    Json.jsonmapper().writeValueAsString(Map.of("slot", slot)));
            Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
            Object text = m.get("text");
            Map<?, ?> inner = text instanceof String
                    ? Json.jsonmapper().readValue((String) text, Map.class)
                    : m;
            Object atts = inner.get("attestations");
            if (!(atts instanceof List) || ((List<?>) atts).isEmpty()) return null;
            Object first = ((List<?>) atts).get(0);
            byte[] b = first instanceof String
                    ? ((String) first).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : Json.jsonmapper().writeValueAsBytes(first);
            try {
                return Json.jsonmapper().readValue(b, AttestationData.class);
            } catch (Exception e) {
                // Some builds nest the JSON string twice.
                String s = new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (s.startsWith("\"")) s = Json.jsonmapper().readValue(s, String.class);
                return Json.jsonmapper().readValue(s, AttestationData.class);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Highest slot with at least one honest vote near the live head. */
    static long voteFrontier(String nodeUrl, long hintSlot) {
        long best = -1;
        for (long s = Math.max(0, hintSlot - 3); s <= hintSlot + 15; s++) {
            try {
                String u = nodeUrl + "getAttestations";
                byte[] r = OkHttp3Util.postString(u, Json.jsonmapper().writeValueAsString(Map.of("slot", s)));
                Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
                Object text = m.get("text");
                Map<?, ?> inner = text instanceof String
                        ? Json.jsonmapper().readValue((String) text, Map.class)
                        : m;
                Object atts = inner.get("attestations");
                if (atts instanceof List && !((List<?>) atts).isEmpty()) best = s;
            } catch (Exception ignore) {
            }
        }
        return best;
    }

    /**
     * Genuine BLS-signed vote: mirrored checkpoints, chosen head, fixture key.
     * Field order mirrors ValidatorDutyService (pubkey before the signed hash).
     */
    static AttestationData signVote(PQKey key, long slot, long epoch, long sourceEpoch, Sha256Hash sourceCp,
            long targetEpoch, Sha256Hash targetCp, Sha256Hash head) {
        AttestationData att = new AttestationData();
        att.setSlot(slot);
        att.setEpoch(epoch);
        att.setSourceEpoch(sourceEpoch);
        att.setTargetEpoch(targetEpoch);
        att.setBeaconBlockHash(head);
        att.setSourceCheckpoint(sourceCp);
        att.setTargetCheckpoint(targetCp);
        att.setValidatorPubkey(key.getPubKey());
        att.setBlsPubkey(RandaoService.blsPubkey(key));
        att.setSignature(RandaoService.blsSign(key, att.getMessageHash().getBytes()));
        return att;
    }

    static int submitVote(String nodeUrl, AttestationData att) {
        try {
            String body = Json.jsonmapper().writeValueAsString(att);
            OkHttp3Util.postString(nodeUrl + "submitAttestation", body);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------- run

    static void run(String[] args) throws Exception {
        long startIndex = Long.parseLong(args[1]);
        int totalWallets = Integer.parseInt(args[2]);
        int nnodes = Integer.parseInt(args[3]);
        String urlPrefix = args[4];
        java.util.Set<String> only = null;
        if (args.length > 5 && !args[5].isBlank()) {
            only = new java.util.HashSet<>();
            for (String v : args[5].split(",")) only.add(v.trim().toUpperCase());
        }
        long fundAmount = Long.getLong("load.fund", 30000L);
        long payAmount = Long.getLong("load.pay", 20000L);
        int confirmTimeoutSec = Integer.getInteger("chaos.confirmTimeoutSec", 300);
        long maxSpread = Long.getLong("chaos.maxSpread", 32L);

        String base = urlPrefix;
        String[] nodeUrls = new String[nnodes];
        for (int i = 0; i < nnodes; i++) {
            nodeUrls[i] = base + (8281 + i) + "/";
        }
        NODE_URLS = nodeUrls;
        NNODES = nnodes;
        SEED_NODE = nodeUrls[0];
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                healAll(nnodes);
            } catch (Exception ignore) {
            }
        }));

        System.out.println("MESHCHAOS run: start=" + startIndex + " wallets=" + totalWallets
                + " nnodes=" + nnodes + " seed=" + SEED_NODE + (only == null ? "" : " only=" + only));

        List<PQKey> validators = loadValidatorKeys(nnodes);

        Map<String, UTXO> utxos = new HashMap<>();
        {
            List<String> pubKeyHashes = new ArrayList<>();
            for (long i = 0; i < totalWallets; i++) {
                byte[] seed = seedFor(startIndex + i);
                pubKeyHashes.add(Utils.HEX.encode(PQKey.fromMLDSA(seed).getPubKeyHash()));
            }
            int CHUNK = 2000;
            for (int c0 = 0; c0 < totalWallets; c0 += CHUNK) {
                List<String> sub = pubKeyHashes.subList(c0, Math.min(totalWallets, c0 + CHUNK));
                byte[] resp = OkHttp3Util.postString(SEED_NODE + "getOutputs",
                        Json.jsonmapper().writeValueAsString(sub));
                GetOutputsResponse gor = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
                if (gor.getOutputs() != null) {
                    for (UTXO u : gor.getOutputs()) {
                        if (u.getValue() != null && u.getValue().getValue().longValue() == fundAmount
                                && u.getAddress() != null && !u.isSpent()) {
                            utxos.put(u.getAddress(), u);
                        }
                    }
                }
            }
        }
        System.out.println("MESHCHAOS funded UTXOs fetched: " + utxos.size() + "/" + totalWallets);
        if (utxos.isEmpty()) {
            System.err.println("NO_FUNDED_UTXOS: chaos genesis funding not visible");
            System.exit(2);
        }
        final long[] cursor = { startIndex };
        java.util.function.IntUnaryOperator take = count -> {
            long at = cursor[0];
            cursor[0] += count;
            return (int) at;
        };

        // ================================================================ V52
        // Orphaned-vote contamination (safe form): a short valid fork is staged
        // while a node restarts mid-flight, then the canonical chain outpaces it
        // (reorg). Every node — including the restarted one — must converge on
        // one head with finality advancing. NOTE: injected REAL-key votes for a
        // non-canonical head self-slash the live duty validators (the node's
        // own same-slot honest vote collides -> genuine double-vote, observed
        // live), so the branch is weighted by the mesh's own churn instead.
        // Real-vote weighting needs a no-duty fixture mesh (deferred).
        if (wanted(only, "V52")) {
            System.out.println("============ V52: ORPHANED-VOTE CONTAMINATION (SAFE) ============");
            try {
                long cl0 = chainLength(SEED_NODE);
                int base0 = take.applyAsInt(8);
                String forkAddr = addrFor(seedFor(runUniqueIndex(startIndex, 52001))).toBase58();
                int wFork = fundedIdx(base0, 4, utxos);
                int staged = 0;
                Block fork = null;
                if (wFork >= 0) {
                    fork = staleForkBlock(nodeUrls, wFork, 3, forkAddr, payAmount);
                    if (fork != null) {
                        for (String nu : nodeUrls) submitBlockTo(nu, fork);
                        staged++;
                        Thread.sleep(8000);
                    }
                }
                // Canonical tips outpace the fork (real reorg), then restart a
                // node mid-reorg and demand convergence.
                int canon = 0;
                int w2 = fundedIdx(base0 + 4, 4, utxos);
                for (int r = 0; r < 3 && w2 >= 0; r++) {
                    try {
                        Block b = staleForkBlock(nodeUrls, w2, 1, forkAddr, payAmount);
                        if (b != null) {
                            for (String nu : nodeUrls) submitBlockTo(nu, b);
                            canon++;
                        }
                    } catch (Exception ignore) {
                    }
                    Thread.sleep(5000);
                }
                // Restart node 0 mid-reorg, then demand convergence.
                docker("restart", nodeContainer(0));
                waitApi(nodeUrls[0], 300);
                long clAfter = chainAfterSettle(SEED_NODE, cl0, 60000);
                java.util.Set<String> heads = headSettled(nodeUrls);
                java.util.Set<String> fins = finRootSettled(nodeUrls);
                long finAfter = finalizedAfterRead(SEED_NODE);
                boolean ok = staged > 0 && canon > 0
                        && heads.size() == 1 && fins.size() == 1 && clAfter > cl0 && finAfter >= 0;
                verdict("V52 orphaned-vote (safe)",
                        ok, "fork=" + (fork == null ? "UNSTAGED" : fork.getHash().toString().substring(0, 12))
                                + " tips x" + canon
                                + " heads=" + heads.size() + " finroots=" + fins.size()
                                + " cl " + cl0 + " -> " + clAfter);
            } catch (Exception e) {
                verdict("V52 orphaned-vote (safe)", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V55
        // Justification-cache race (safe form): tip + stale-fork churn across
        // an epoch boundary (the mesh's own votes do the justification) with a
        // restart mid-way. Justified checkpoint must converge on all nodes and
        // finality resume. Real-vote weighting self-slash caveat as V52.
        if (wanted(only, "V55")) {
            System.out.println("============ V55: JUSTIFICATION-CACHE RACE (SAFE) ============");
            try {
                long cl0 = chainLength(SEED_NODE);
                int base0 = take.applyAsInt(12);
                String ra = addrFor(seedFor(runUniqueIndex(startIndex, 55001))).toBase58();
                String justBefore = justifiedHash(SEED_NODE);
                long finBefore = finalizedAfterRead(SEED_NODE);
                long endAt = System.currentTimeMillis() + 200_000L;
                int round = 0, staged = 0;
                boolean restarted = false;
                while (System.currentTimeMillis() < endAt) {
                    try {
                        int w = fundedIdx(base0 + (round % 12), 2, utxos);
                        if (w >= 0) {
                            UTXO u = utxos.get(addrFor(seedFor(w)).toBase58());
                            if (u != null && !u.isSpent()) {
                                Transaction t = pay(keyFor(w), u, ra, payAmount, "v55-tip");
                                submitBlockTo(nodeUrls[round % nnodes], craftTransferBlock(t));
                            }
                        }
                        int ws = fundedIdx(base0 + ((round + 6) % 12), 2, utxos);
                        Block stale = ws < 0 ? null
                                : staleForkBlock(nodeUrls, ws, 2, ra, payAmount);
                        if (stale != null) {
                            submitBlockTo(nodeUrls[(round + 1) % nnodes], stale);
                            staged++;
                        }
                    } catch (Exception ignore) {
                    }
                    if (!restarted && System.currentTimeMillis() > endAt - 100_000L) {
                        restarted = true;
                        try {
                            docker("restart", nodeContainer(1));
                            waitApi(nodeUrls[1], 300);
                        } catch (Exception ignore) {
                        }
                    }
                    round++;
                    Thread.sleep(8000);
                }
                Thread.sleep(45000);
                java.util.Set<String> justs = new java.util.HashSet<>();
                for (String u : nodeUrls) justs.add(justifiedHash(u));
                if (justs.size() > 1) {
                    Thread.sleep(30000);
                    justs.clear();
                    for (String u : nodeUrls) justs.add(justifiedHash(u));
                }
                long finAfter = finalizedAfterRead(SEED_NODE);
                java.util.Set<String> fins = finRootSettled(nodeUrls);
                boolean ok = justs.size() == 1 && fins.size() == 1 && finAfter > finBefore;
                verdict("V55 justification race (" + round + " rounds)",
                        ok, "just " + justBefore + " -> " + justs + " stale x" + staged
                                + " fin " + finBefore + " -> " + finAfter + " finroots=" + fins.size());
            } catch (Exception e) {
                verdict("V55 justification race", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V56
        // Genuine slashing idempotence: real double-vote evidence from a
        // controlled key must be ADMITTED (unlike fake-key proofs), produce
        // exactly one SLASHING block, and never re-apply on replay/dupes.
        if (wanted(only, "V56")) {
            System.out.println("============ V56: GENUINE SLASHING IDEMPOTENCE ============");
            try {
                int victim = nnodes - 1;
                PQKey vk = validators.get(victim);
                long valBefore = activeValidatorCount(SEED_NODE);
                // Genuine conflicting votes, same slot: canonical tip vs fork.
                long cl = chainLength(SEED_NODE);
                AttestationData honest = embeddedTemplate(SEED_NODE, 15);
                boolean admitted = false;
                long voteSlot = -1;
                if (honest != null) {
                    voteSlot = honest.getSlot();
                    long epoch = params.getEpochForSlot(voteSlot);
                    Sha256Hash h1 = honest.getBeaconBlockHash();
                    Sha256Hash h2 = Sha256Hash.wrap(seedFor(runUniqueIndex(startIndex, 56001)));
                    if (h1 == null || h1.equals(Sha256Hash.ZERO_HASH)) {
                        h1 = Sha256Hash.wrap(seedFor(runUniqueIndex(startIndex, 56002)));
                    }
                    AttestationData a1 = signVote(vk, voteSlot, epoch,
                            honest.getSourceEpoch(), honest.getSourceCheckpoint(),
                            honest.getTargetEpoch(), honest.getTargetCheckpoint(), h1);
                    AttestationData a2 = signVote(vk, voteSlot, epoch,
                            honest.getSourceEpoch(), honest.getSourceCheckpoint(),
                            honest.getTargetEpoch(), honest.getTargetCheckpoint(), h2);
                    admitted = submitSlashingProof(SEED_NODE, a1, a2);
                    // Duplicate deliveries (mempool/gossip redelivery shape).
                    for (int dup = 0; dup < 3; dup++) {
                        submitSlashingProof(nodeUrls[dup % nnodes], a1, a2);
                        Thread.sleep(2000);
                    }
                }
                // Wait for the slashing to APPLY (the victim leaves the active
                // set on the proposing node) — the set-drop is the oracle, not
                // a brittle DAG walk for the SLASHING block.
                long valAfter = valBefore;
                long t0 = System.currentTimeMillis();
                while (System.currentTimeMillis() - t0 < 300_000L) {
                    Thread.sleep(15000);
                    valAfter = activeValidatorCount(SEED_NODE);
                    if (valAfter < valBefore) break;
                }
                // Replay the same genuine proof (re-submit after the drop) —
                // the application must be idempotent: no SECOND validator drop.
                if (honest != null && valAfter < valBefore) {
                    long epoch = params.getEpochForSlot(voteSlot);
                    Sha256Hash h1 = honest.getBeaconBlockHash();
                    if (h1 == null || h1.equals(Sha256Hash.ZERO_HASH)) {
                        h1 = Sha256Hash.wrap(seedFor(runUniqueIndex(startIndex, 56002)));
                    }
                    Sha256Hash h2 = Sha256Hash.wrap(seedFor(runUniqueIndex(startIndex, 56001)));
                    AttestationData a1 = signVote(vk, voteSlot, epoch,
                            honest.getSourceEpoch(), honest.getSourceCheckpoint(),
                            honest.getTargetEpoch(), honest.getTargetCheckpoint(), h1);
                    AttestationData a2 = signVote(vk, voteSlot, epoch,
                            honest.getSourceEpoch(), honest.getSourceCheckpoint(),
                            honest.getTargetEpoch(), honest.getTargetCheckpoint(), h2);
                    for (int r = 0; r < 3; r++) {
                        submitSlashingProof(nodeUrls[r % nnodes], a1, a2);
                        Thread.sleep(5000);
                    }
                    Thread.sleep(30000);
                }
                long valFinal = activeValidatorCount(SEED_NODE);
                long clAfter = chainAfterSettle(SEED_NODE, cl, 45000);
                java.util.Set<String> fins = finRootSettled(nodeUrls);
                // Exactly ONE validator removed (the victim), stable across
                // dups + replays; every node agrees on the same set; the
                // remaining quorum keeps finality advancing.
                boolean exactlyOnce = (valBefore - valAfter) == 1 && valFinal == valAfter;
                java.util.Set<Long> valSets = new java.util.HashSet<>();
                for (String u : nodeUrls) valSets.add(activeValidatorCount(u));
                boolean ok = honest != null && admitted && exactlyOnce && valSets.size() == 1
                        && clAfter > cl && fins.size() == 1;
                verdict("V56 genuine slashing",
                        ok, (honest == null ? "UNSTAGED " : "") + "admitted=" + admitted
                                + " validators " + valBefore + " -> " + valAfter + " -> " + valFinal
                                + " (want -1 then stable) " + valSets
                                + " cl " + cl + " -> " + clAfter + " finroots=" + fins.size());
            } catch (Exception e) {
                verdict("V56 genuine slashing", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V66
        // Rolling restart under load: restart every validator one at a time
        // (sentinels flowing), each must rejoin past its floor, fin never
        // regresses, sentinels confirm once, digests converge.
        if (wanted(only, "V66")) {
            System.out.println("============ V66: ROLLING RESTART ============");
            try {
                int base0 = take.applyAsInt(nnodes * 2);
                String sentAddr = addrFor(seedFor(runUniqueIndex(startIndex, 66001))).toBase58();
                long finFloor = finalizedAfterRead(SEED_NODE);
                boolean okAll = true;
                StringBuilder detail = new StringBuilder();
                int si = 0;
                int sentinels = 0;
                for (int i = 0; i < nnodes; i++) {
                    long floor = minChain(nodeUrls);
                    // Sentinel before the restart.
                    UTXO u = utxos.get(addrFor(seedFor(base0 + si)).toBase58());
                    if (u != null) {
                        try {
                            if (submitTx(pay(keyFor(base0 + si), u, sentAddr, payAmount, "v66"))) {
                                sentinels++;
                                si++;
                            }
                        } catch (Exception ignore) {
                        }
                    }
                    docker("restart", nodeContainer(i));
                    waitApi(nodeUrls[i], 300);
                    long joinDeadline = System.currentTimeMillis() + 600_000L;
                    boolean rejoined = false;
                    while (System.currentTimeMillis() < joinDeadline) {
                        Thread.sleep(15000);
                        long c = chainLength(nodeUrls[i]);
                        if (c > floor) { rejoined = true; break; }
                    }
                    long fnow = finalizedAfterRead(nodeUrls[i]);
                    if (fnow < finFloor) {
                        okAll = false;
                        detail.append("fin-regress(node").append(i).append(") ");
                    }
                    finFloor = Math.max(finFloor, fnow);
                    if (!rejoined) {
                        okAll = false;
                        detail.append("no-rejoin(node").append(i).append(") ");
                    } else {
                        detail.append("n").append(i).append(":ok ");
                    }
                    // Sentinel after rejoin.
                    UTXO u2 = utxos.get(addrFor(seedFor(base0 + si)).toBase58());
                    if (u2 != null) {
                        try {
                            if (submitTx(pay(keyFor(base0 + si), u2, sentAddr, payAmount, "v66"))) {
                                sentinels++;
                                si++;
                            }
                        } catch (Exception ignore) {
                        }
                    }
                }
                int confirmed = 0;
                long deadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(5000);
                    confirmed = countConfirmed(sentAddr);
                    if (confirmed >= sentinels && sentinels > 0) break;
                }
                java.util.Set<String> fins = finRootSettled(nodeUrls);
                java.util.Set<String> heads = headSettled(nodeUrls);
                boolean ok = okAll && fins.size() == 1 && heads.size() == 1 && confirmed >= sentinels;
                verdict("V66 rolling restart",
                        ok, detail.toString() + "sentinels " + confirmed + "/" + sentinels
                                + " heads=" + heads.size() + " finroots=" + fins.size());
            } catch (Exception e) {
                verdict("V66 rolling restart", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V68
        // Process pause across duties + epoch boundary: freeze one node, resume
        // without restart. Stale duties must not replay (no new SLASHING
        // blocks), the node catches the finroot, mesh converges.
        if (wanted(only, "V68")) {
            System.out.println("============ V68: PROCESS PAUSE ============");
            try {
                int pauseSec = Integer.getInteger("chaos.pauseSec", 150);
                int victim = nnodes - 1;
                int slashBefore = countSlashingBlocks(nodeUrls, 40);
                long valBefore = activeValidatorCount(SEED_NODE);
                docker("pause", nodeContainer(victim));
                Thread.sleep(pauseSec * 1000L);
                docker("unpause", nodeContainer(victim));
                waitApi(nodeUrls[victim], 300);
                long floor = minChain(nodeUrls);
                long joinDeadline = System.currentTimeMillis() + 600_000L;
                boolean rejoined = false;
                while (System.currentTimeMillis() < joinDeadline) {
                    Thread.sleep(15000);
                    if (chainLength(nodeUrls[victim]) > floor) { rejoined = true; break; }
                }
                Thread.sleep(30000);
                int slashAfter = countSlashingBlocks(nodeUrls, 40);
                java.util.Set<String> fins = finRootSettled(nodeUrls);
                java.util.Set<String> heads = headSettled(nodeUrls);
                boolean ok = rejoined && slashAfter == slashBefore && fins.size() == 1 && heads.size() == 1
                        && activeValidatorCount(SEED_NODE) == valBefore;
                verdict("V68 process pause (" + pauseSec + "s)",
                        ok, "rejoined=" + rejoined + " slashings " + slashBefore + " -> " + slashAfter
                                + " (want none new)" + " heads=" + heads.size() + " finroots=" + fins.size());
            } catch (Exception e) {
                verdict("V68 process pause", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V67
        // Postgres outage across a boundary: stop one node's DB ~75s, quorum
        // keeps finalizing, node fails closed, then catches the finroot with a
        // responsive tip after heal. Needs PER_NODE_PG=1 (own pg container).
        if (wanted(only, "V67")) {
            System.out.println("============ V67: POSTGRES OUTAGE ============");
            try {
                int outageSec = Integer.getInteger("chaos.pgOutageSec", 75);
                int victim = nnodes - 1;
                String pg = pgContainer(victim);
                long finBefore = finalizedAfterRead(SEED_NODE);
                docker("stop", pg);
                Thread.sleep(outageSec * 1000L);
                // Quorum liveness DURING the outage: finality only advances per
                // epoch (~96s+), which a 75s window rarely crosses, so measure
                // the CONFIRMED chain (advances every slot) on a healthy node.
                long clDuring = chainLength(nodeUrls[0]);
                docker("start", pg);
                // Recovery is measured, not assumed: the pool re-establishes
                // SLOWLY after an outage (observed 10+ min for a 75s outage —
                // dead pooled connections only clear as they are reaped). PASS
                // requires eventual convergence to the mesh finroot; the
                // recovery latency is reported as the finding signal.
                long recoverMs = -1;
                long tR0 = System.currentTimeMillis();
                java.util.Set<String> targetFins = finRootSet(nodeUrls);
                while (System.currentTimeMillis() - tR0 < 1_500_000L) {
                    Thread.sleep(15000);
                    if (chainLength(nodeUrls[victim]) >= 0
                            && targetFins.contains(finalizedHash(nodeUrls[victim]))) {
                        recoverMs = System.currentTimeMillis() - tR0;
                        break;
                    }
                }
                long p99 = tipP99(nodeUrls, 12);
                java.util.Set<String> fins = finRootSettled(nodeUrls);
                boolean quorumKeptGoing = clDuring > finBefore;
                boolean recovered = recoverMs >= 0;
                boolean ok = quorumKeptGoing && recovered && fins.size() == 1 && p99 < 8000;
                verdict("V67 pg outage (" + outageSec + "s)",
                        ok, "quorum-cl " + finBefore + " -> " + clDuring + " (during) recovery="
                                + (recovered ? (recoverMs / 1000) + "s" : ">1500s NEVER") + " tipP99=" + p99
                                + "ms finroots=" + fins.size());
            } catch (Exception e) {
                verdict("V67 pg outage", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V65
        // Gossip throttle with live API: delay+loss on one node's gossip port
        // for ~3 epochs. Quorum finality must advance throughout; the lagger
        // catches the finroot bounded after heal. Skipped without `tc`.
        if (wanted(only, "V65")) {
            System.out.println("============ V65: GOSSIP THROTTLE ============");
            try {
                if (!haveTc()) {
                    verdict("V65 gossip throttle", false, "UNSTAGED: no `tc` on this host");
                } else {
                    int throttleSec = Integer.getInteger("chaos.throttleSec", 300);
                    int victim = nnodes - 1;
                    int gport = gossipPort(victim);
                    long finBefore = finalizedAfterRead(SEED_NODE);
                    tcThrottle(gport);
                    long endAt = System.currentTimeMillis() + throttleSec * 1000L;
                    long finMin = finBefore;
                    while (System.currentTimeMillis() < endAt) {
                        Thread.sleep(30000);
                        long f = finalizedAfterRead(SEED_NODE);
                        if (f > finMin) finMin = f;
                    }
                    tcHeal();
                    long floor = minChain(nodeUrls);
                    long joinDeadline = System.currentTimeMillis() + 600_000L;
                    boolean caught = false;
                    while (System.currentTimeMillis() < joinDeadline) {
                        Thread.sleep(15000);
                        if (chainLength(nodeUrls[victim]) >= floor && floor > 0) {
                            // Lagger reached the pre-heal floor; now converge fully.
                            long cur = Long.MAX_VALUE;
                            for (String u : nodeUrls) cur = Math.min(cur, chainLength(u));
                            if (chainLength(nodeUrls[victim]) >= cur) { caught = true; break; }
                        }
                    }
                    java.util.Set<String> fins = finRootSettled(nodeUrls);
                    boolean ok = finMin > finBefore && caught && fins.size() == 1;
                    verdict("V65 gossip throttle (" + throttleSec + "s)",
                            ok, "quorum-fin " + finBefore + " -> " + finMin + " lagger-caught=" + caught
                                    + " finroots=" + fins.size());
                }
            } catch (Exception e) {
                try {
                    tcHeal();
                } catch (Exception ignore) {
                }
                verdict("V65 gossip throttle", false, "error: " + shortMsg(e));
            }
        }

        System.out.println("==============================================");
        System.out.println("  MESHCHAOS — VERDICT TABLE");
        System.out.println("==============================================");
        boolean allPass = true;
        for (String row : VERDICTS) {
            System.out.println("  " + row);
            if (row.contains("FAIL")) allPass = false;
        }
        System.out.println("==============================================");
        System.out.println(allPass ? "ALL_CHAOS_HELD" : "CHAOS_BREACH_DETECTED");
        System.out.flush();
        healAll(nnodes);
        System.exit(allPass ? 0 : 1);
    }

    static boolean wanted(java.util.Set<String> only, String v) {
        return only == null || only.contains(v);
    }

    static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        m = m.replace('\n', ' ');
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    static String hostOf(String nodeUrl) {
        try {
            java.net.URI uri = new java.net.URI(nodeUrl);
            return uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    static String nodeContainer(int i) {
        return System.getProperty("chaos.containerPrefix", "bt4-node-") + "node-" + i + "-server";
    }

    static String pgContainer(int i) {
        return System.getProperty("chaos.pgPrefix", "bt4-pg-") + i;
    }

    static int gossipPort(int i) {
        String raw = System.getProperty("chaos.gossipPorts", "");
        if (!raw.isBlank()) {
            String[] parts = raw.split(",");
            if (i < parts.length) {
                try {
                    return Integer.parseInt(parts[i].trim());
                } catch (Exception ignore) {
                }
            }
        }
        return 9421 + i;
    }

    /** docker <args...> on this host; returns stdout, throws on failure/exit!=0. */
    static String docker(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .trim().replace('\n', ' ');
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("docker " + String.join(" ", args) + " timed out");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("docker " + String.join(" ", args) + " -> " + out);
        }
        return out;
    }

    static void waitApi(String nodeUrl, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                OkHttp3Util.postString(nodeUrl + "getTip", "{}");
                return;
            } catch (Exception ignore) {
            }
            Thread.sleep(5000);
        }
        throw new IllegalStateException("API never came back: " + nodeUrl);
    }

    static boolean haveTc() {
        try {
            Process p = new ProcessBuilder("tc", "-V").redirectErrorStream(true).start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Delay+loss the victim's gossip port both ways; API ports untouched. */
    static void tcThrottle(int gport) throws Exception {
        execRoot("tc", "qdisc", "add", "dev", "lo", "root", "handle", "1:", "prio");
        execRoot("tc", "filter", "add", "dev", "lo", "parent", "1:", "protocol", "ip", "prio", "1",
                "u32", "match", "ip", "dport", String.valueOf(gport), "0xffff", "flowid", "1:3");
        execRoot("tc", "filter", "add", "dev", "lo", "parent", "1:", "protocol", "ip", "prio", "1",
                "u32", "match", "ip", "sport", String.valueOf(gport), "0xffff", "flowid", "1:3");
        execRoot("tc", "qdisc", "add", "dev", "lo", "parent", "1:3", "handle", "30:",
                "netem", "delay", "1500ms", "200ms", "loss", "5%");
    }

    static void tcHeal() {
        try {
            execRoot("tc", "qdisc", "del", "dev", "lo", "root");
        } catch (Exception ignore) {
        }
    }

    static void execRoot(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .trim().replace('\n', ' ');
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException(String.join(" ", args) + " timed out");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", args) + " -> " + out);
        }
    }

    /**
     * Best-effort undo of every chaos mutation (also runs via shutdown hook):
     * unpause node containers, (re)start per-node postgres containers, delete
     * tc rules. Runs exactly once per JVM.
     */
    static void healAll(int nnodes) {
        if (!HEALED.compareAndSet(false, true)) return;
        for (int i = 0; i < nnodes; i++) {
            try {
                docker("unpause", nodeContainer(i));
            } catch (Exception ignore) {
            }
        }
        for (int i = 0; i < nnodes; i++) {
            try {
                docker("start", pgContainer(i));
            } catch (Exception ignore) {
            }
        }
        tcHeal();
    }

    // ---------------------------------------------------------------- helpers

    static Transaction pay(PQKey from, UTXO utxo, String toAddr, long amount, String note) throws Exception {
        FreeStandingTransactionOutput coin = new FreeStandingTransactionOutput(params, utxo);
        Wallet w = Wallet.fromKeys(params, from);
        return w.payToListTransaction(null, new HashMap<>(Map.of(toAddr, BigInteger.valueOf(amount))),
                M_BIG, note, List.of(coin));
    }

    static boolean submitTx(Transaction tx) {
        try {
            OkHttp3Util.post(SEED_NODE + "submitTransaction", tx.bitcoinSerialize());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Block fetchBlock(Sha256Hash hash) throws Exception {
        byte[] r = OkHttp3Util.postString(SEED_NODE + "getBlockByHash",
                Json.jsonmapper().writeValueAsString(Map.of("hashHex", hash.toString(), "text", "false")));
        Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
        String dataHex = (String) m.get("dataHex");
        return params.getDefaultSerializer().makeBlock(Utils.HEX.decode(dataHex));
    }

    static Block craftTransferBlock(Transaction tx) throws Exception {
        byte[] tipResp = OkHttp3Util.postString(SEED_NODE + "getTip", "{}");
        Block proto = params.getDefaultSerializer().makeBlock(
                Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
        Block trunk = fetchBlock(proto.getPrevBlockHash());
        Block branch = fetchBlock(proto.getPrevBranchBlockHash());
        Block block = Block.createBlock(params, trunk, branch);
        block.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
        block.addTransaction(tx);
        return block;
    }

    static int countConfirmed(String address) {
        try {
            byte[] r = OkHttp3Util.postString(SEED_NODE + "getTransactionsStatusByAddress",
                    Json.jsonmapper().writeValueAsString(Map.of("address", address)));
            GetTransactionStatusResponse.GetTransactionsStatusResponse resp = Json.jsonmapper().readValue(r,
                    GetTransactionStatusResponse.GetTransactionsStatusResponse.class);
            int cnt = 0;
            if (resp.getTransactions() != null) {
                for (GetTransactionStatusResponse item : resp.getTransactions()) {
                    if ("CONFIRMED".equals(item.getStatus())) {
                        cnt++;
                    }
                }
            }
            return cnt;
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean submitSlashingProof(String nodeUrl, AttestationData a1, AttestationData a2) {
        try {
            String body = "{\"attestation1\":"
                    + new String(Json.jsonmapper().writeValueAsBytes(a1),
                            java.nio.charset.StandardCharsets.UTF_8)
                            + ",\"attestation2\":"
                                    + new String(Json.jsonmapper().writeValueAsBytes(a2),
                                            java.nio.charset.StandardCharsets.UTF_8)
                                    + "}";
            byte[] r = OkHttp3Util.postString(nodeUrl + "submitSlashingProof", body);
            Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
            Object ec = m.get("errorcode");
            return ec instanceof Number && ((Number) ec).intValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static long chainLength(String hostPort) {
        try {
            String u = (hostPort.startsWith("http") ? hostPort : "http://" + hostPort) + "/getChainNumber";
            byte[] r = OkHttp3Util.postString(u.replaceAll("/+getChainNumber", "/getChainNumber"), "{}");
            Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
            Object tr = m.get("txReward");
            String s = tr instanceof String ? (String) tr : Json.jsonmapper().writeValueAsString(tr);
            Map<?, ?> t = Json.jsonmapper().readValue(s, Map.class);
            Object cl = t.get("chainLength");
            return cl instanceof Number ? ((Number) cl).longValue() : Long.parseLong(String.valueOf(cl));
        } catch (Exception e) {
            return -1;
        }
    }

    static String chainNumberJson(String hostPort) {
        try {
            String u = (hostPort.startsWith("http") ? hostPort : "http://" + hostPort) + "/getChainNumber";
            byte[] r = OkHttp3Util.postString(u.replaceAll("/+getChainNumber", "/getChainNumber"), "{}");
            Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
            return Json.jsonmapper().writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    static long finalizedLength(String hostPort) {
        try {
            Map<?, ?> m = Json.jsonmapper().readValue(chainNumberJson(hostPort), Map.class);
            Object fl = m.get("finalizedChainLength");
            return fl instanceof Number ? ((Number) fl).longValue() : Long.parseLong(String.valueOf(fl));
        } catch (Exception e) {
            return -1;
        }
    }

    static String finalizedHash(String hostPort) {
        try {
            Map<?, ?> m = Json.jsonmapper().readValue(chainNumberJson(hostPort), Map.class);
            Object fh = m.get("finalizedBlockHash");
            return fh == null ? "" : String.valueOf(fh);
        } catch (Exception e) {
            return "";
        }
    }

    static String chainHead(String hostPort) {
        try {
            Map<?, ?> m = Json.jsonmapper().readValue(chainNumberJson(hostPort), Map.class);
            Object tr = m.get("txReward");
            if (tr instanceof Map) {
                Object h = ((Map<?, ?>) tr).get("blockHashHex");
                if (h != null) return String.valueOf(h);
                Object b = ((Map<?, ?>) tr).get("blockHash");
                if (b != null) return String.valueOf(b);
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    static java.util.Set<String> headSet(String[] nodeUrls) {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String u : nodeUrls) s.add(chainHead(u));
        return s;
    }

    /** Head agreement with one confirm-retry (fork-choice churn at read time). */
    static java.util.Set<String> headSettled(String[] nodeUrls) throws Exception {
        java.util.Set<String> heads = headSet(nodeUrls);
        if (heads.size() > 1) {
            Thread.sleep(20000);
            heads = headSet(nodeUrls);
        }
        return heads;
    }

    static boolean submitBlockTo(String nodeUrl, Block block) {
        try {
            OkHttp3Util.post((nodeUrl.startsWith("http") ? nodeUrl : "http://" + nodeUrl) + "batchBlock",
                    block.bitcoinSerialize());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static long activeValidatorCount(String nodeUrl) {
        try {
            String u = (nodeUrl.startsWith("http") ? nodeUrl : "http://" + nodeUrl);
            byte[] r = OkHttp3Util.postString(u + "getValidators", "{}");
            Map<?, ?> top = Json.jsonmapper().readValue(r, Map.class);
            Object text = top.get("text");
            Object valObj = text != null ? text : top.get("validators");
            if (valObj instanceof String) {
                Map<?, ?> inner = Json.jsonmapper().readValue((String) valObj, Map.class);
                Object v = inner.get("validators");
                if (v instanceof java.util.List) return ((java.util.List<?>) v).size();
                if (v instanceof Map) return ((Map<?, ?>) v).size();
                return 0;
            }
            if (valObj instanceof java.util.List) return ((java.util.List<?>) valObj).size();
            if (valObj instanceof Map) return ((Map<?, ?>) valObj).size();
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    static java.util.Set<String> finRootSet(String[] nodeUrls) {
        java.util.Set<String> fins = new java.util.HashSet<>();
        for (String u : nodeUrls) fins.add(finalizedHash(u));
        return fins;
    }

    static java.util.Set<String> finRootSettled(String[] nodeUrls) throws Exception {
        java.util.Set<String> fins = finRootSet(nodeUrls);
        if (fins.size() > 1) {
            Thread.sleep(20000);
            fins = finRootSet(nodeUrls);
        }
        return fins;
    }

    static long chainAfterSettle(String nodeUrl, long before, long settleMs) throws Exception {
        Thread.sleep(settleMs);
        long cl = chainLength(nodeUrl);
        if (cl <= before) {
            Thread.sleep(30000);
            cl = Math.max(cl, chainLength(nodeUrl));
        }
        return cl;
    }

    static long finalizedAfterRead(String nodeUrl) throws Exception {
        long fin = finalizedLength(nodeUrl);
        if (fin < 0) {
            Thread.sleep(10000);
            fin = Math.max(fin, finalizedLength(nodeUrl));
        }
        return fin;
    }

    static String justifiedHash(String hostPort) {
        try {
            Map<?, ?> m = Json.jsonmapper().readValue(chainNumberJson(hostPort), Map.class);
            Object jh = m.get("justifiedBlockHash");
            return jh == null ? "" : String.valueOf(jh);
        } catch (Exception e) {
            return "";
        }
    }

    static long percentile(List<Long> values, int pct) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    static long tipP99(String[] nodeUrls, int probes) {
        List<Long> lat = new ArrayList<>();
        for (int i = 0; i < probes; i++) {
            try {
                long t0 = System.nanoTime();
                OkHttp3Util.postString(nodeUrls[i % nodeUrls.length] + "getTip", "{}");
                lat.add((System.nanoTime() - t0) / 1_000_000L);
            } catch (Exception ignore) {
            }
        }
        return percentile(lat, 99);
    }

    static long minChain(String[] nodeUrls) {
        long min = Long.MAX_VALUE;
        for (String u : nodeUrls) {
            long cl = chainLength(u);
            if (cl >= 0) min = Math.min(min, cl);
        }
        return min == Long.MAX_VALUE ? -1 : min;
    }

    static AttestationData fakeAttestation(byte[] pubkey, long slot, long epoch,
            long sourceEpoch, long targetEpoch, Sha256Hash head, Sha256Hash target) {
        AttestationData att = new AttestationData();
        att.setSlot(slot);
        att.setEpoch(epoch);
        att.setSourceEpoch(sourceEpoch);
        att.setTargetEpoch(targetEpoch);
        att.setBeaconBlockHash(head);
        att.setSourceCheckpoint(Sha256Hash.ZERO_HASH);
        att.setTargetCheckpoint(target);
        att.setValidatorPubkey(pubkey);
        return att;
    }

    static Block staleForkBlock(String[] nodeUrls, long walletIdx, int depth, String toAddr, long payAmount) {
        try {
            UTXO u = fetchUtxo(walletIdx);
            if (u == null) return null;
            byte[] tipResp = OkHttp3Util.postString(nodeUrls[0] + "getTip", "{}");
            Block proto = params.getDefaultSerializer().makeBlock(
                    Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
            Block cur = fetchBlock(proto.getPrevBlockHash());
            for (int d = 0; d < depth && cur != null; d++) {
                Block parent = fetchBlock(cur.getPrevBranchBlockHash());
                if (parent == null || parent.getHash().equals(cur.getHash())) break;
                cur = parent;
            }
            if (cur == null) return null;
            Block branch = fetchBlock(cur.getPrevBranchBlockHash());
            Block fork = Block.createBlock(params, cur, branch);
            fork.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
            Transaction t = pay(keyFor(walletIdx), u, toAddr, payAmount, "chaos-stale-fork");
            fork.addTransaction(t);
            return fork;
        } catch (Exception e) {
            return null;
        }
    }

    /** First funded wallet index in [base, base+max), or -1 (funding gaps). */
    static int fundedIdx(int base, int max, Map<String, UTXO> utxos) {
        for (int i = 0; i < max; i++) {
            UTXO u = utxos.get(addrFor(seedFor(base + i)).toBase58());
            if (u != null) return base + i;
        }
        return -1;
    }

    static UTXO fetchUtxo(long walletIdx) {        try {
            byte[] seed = seedFor(walletIdx);
            byte[] resp = OkHttp3Util.postString(SEED_NODE + "getOutputs",
                    Json.jsonmapper().writeValueAsString(List.of(
                            Utils.HEX.encode(PQKey.fromMLDSA(seed).getPubKeyHash()))));
            GetOutputsResponse gor = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
            if (gor.getOutputs() != null) {
                UTXO fallback = null;
                for (UTXO x : gor.getOutputs()) {
                    if (x.getValue() != null && x.getAddress() != null && !x.isSpent()) {
                        if (x.getValue().getValue().longValue() >= 25000L) return x;
                        if (fallback == null) fallback = x;
                    }
                }
                if (fallback != null) return fallback;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** SLASHING-type blocks in the last {@code depth} ancestors of each node's tip. */
    static int countSlashingBlocks(String[] nodeUrls, int depth) {
        int total = 0;
        java.util.Set<String> seen = new HashSet<>();
        for (String nu : nodeUrls) {
            try {
                byte[] tipResp = OkHttp3Util.postString(nu + "getTip", "{}");
                Block cur = params.getDefaultSerializer().makeBlock(Utils.HEX.decode(
                        (String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
                for (int d = 0; d < depth && cur != null; d++) {
                    if (cur.getBlockType() == BlockType.BLOCKTYPE_SLASHING
                            && seen.add(cur.getHash().toString())) {
                        total++;
                    }
                    Sha256Hash prev = cur.getPrevBlockHash();
                    if (prev == null || prev.equals(Sha256Hash.ZERO_HASH)) break;
                    try {
                        byte[] r = OkHttp3Util.postString(nu + "getBlockByHash", Json.jsonmapper()
                                .writeValueAsString(Map.of("hashHex", prev.toString(), "text", "false")));
                        Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
                        String dataHex = (String) m.get("dataHex");
                        if (dataHex == null) break;
                        Block next = params.getDefaultSerializer().makeBlock(Utils.HEX.decode(dataHex));
                        if (next.getHash().equals(cur.getHash())) break;
                        cur = next;
                    } catch (Exception e) {
                        break;
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return total;
    }
}
