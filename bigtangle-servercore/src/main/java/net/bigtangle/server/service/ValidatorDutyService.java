package net.bigtangle.server.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import net.bigtangle.core.AttestationData;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Utils;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

@Service
public class ValidatorDutyService {

    private static final Logger log = LoggerFactory.getLogger(ValidatorDutyService.class);

    /** Max epochs a validator's justified checkpoint may trail the chain epoch before it must abstain. */
    private static final long ATTEST_MAX_SOURCE_LAG = 8;

    @Autowired
    private SlotService slotService;

    @Autowired
    private CasperService casperService;

    @Autowired
    private net.bigtangle.kafka.KafkaMessageProducer kafkaMessageProducer;

    @Autowired
    private GhostService ghostService;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private StoreService storeService;

    @Value("${pos.validatorKey:}")
    private String configuredValidatorKey;

    /**
     * Whether this node performs validator duties (propose beacons, attest).
     * Disabled on the L0 HTTP server in a split-node setup so only the
     * consensus node proposes — otherwise two processes sharing the same
     * validator key both run the slot tick and produce competing beacons,
     * forking the chain and orphaning transaction blocks (payments stuck in
     * BATCHED). The validator key must STAY configured for stakeDeposit auth.
     */
    @Value("${pos.dutyEnabled:true}")
    private boolean dutyEnabled = true;

    private PQKey validatorKey;

    private long lastDutySlot = -1;

    // Slashing-protection state (PERSISTED — survives restarts). A validator
    // that restarts mid-slot must never sign a second, different beacon or
    // attestation for an already-signed slot: that is equivocation and gets
    // slashed. These records are the local slashing-protection DB.
    private long lastProposedSlot = -1;
    private long lastAttestedSlot = -1;
    private Sha256Hash lastAttestedHead;
    private Sha256Hash lastAttestedTarget;

    @PostConstruct
    public void init() {
        if (configuredValidatorKey != null && !configuredValidatorKey.isEmpty()) {
            try {
                this.validatorKey = PQKey.fromPrivateKeyHex(configuredValidatorKey);
            } catch (Exception e) {
                log.warn("Invalid pos.validatorKey config (expected 64-hex ML-DSA-only or 128-hex dual seed): {}", e.getMessage());
            }
        }
        restoreDutyState();
    }

    /** (Re)loads the persisted slashing-protection records, replacing in-memory state. */
    public void restoreDutyState() {
        lastProposedSlot = -1;
        lastAttestedSlot = -1;
        lastAttestedHead = null;
        lastAttestedTarget = null;
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                byte[] prop = store.getPosState("duty", "proposed_slot");
                if (prop != null) {
                    lastProposedSlot = Long.parseLong(new String(prop, java.nio.charset.StandardCharsets.UTF_8));
                }
                byte[] att = store.getPosState("duty", "attested");
                if (att != null) {
                    String[] p = new String(att, java.nio.charset.StandardCharsets.UTF_8).split(":");
                    if (p.length == 3) {
                        lastAttestedSlot = Long.parseLong(p[0]);
                        lastAttestedHead = Sha256Hash.wrap(Utils.HEX.decode(p[1]));
                        lastAttestedTarget = Sha256Hash.wrap(Utils.HEX.decode(p[2]));
                    }
                }
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.trace("No prior duty state to restore", e);
        }
    }

    /** Slashing protection: never sign a beacon for a slot at or below the last proposed one. */
    public boolean mayPropose(long slot) {
        return slot > lastProposedSlot;
    }

    /**
     * Slashing protection: one attestation per slot. Re-attesting a slot is only
     * permitted with BYTE-IDENTICAL content (same head and target) — any other
     * re-vote for the same slot is a slashable double vote.
     */
    public boolean mayAttest(long slot, Sha256Hash head, Sha256Hash target) {
        if (slot > lastAttestedSlot) {
            return true;
        }
        if (slot < lastAttestedSlot || lastAttestedHead == null || lastAttestedTarget == null) {
            return false;
        }
        return head.equals(lastAttestedHead) && target.equals(lastAttestedTarget);
    }

    private void recordProposed(long slot, BlockStoreInterface store) {
        lastProposedSlot = slot;
        try {
            store.savePosState("duty", "proposed_slot",
                    String.valueOf(slot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Failed to persist proposal duty for slot {}", slot, e);
        }
    }

    private void recordAttested(long slot, Sha256Hash head, Sha256Hash target, BlockStoreInterface store) {
        lastAttestedSlot = slot;
        lastAttestedHead = head;
        lastAttestedTarget = target;
        try {
            store.savePosState("duty", "attested",
                    (slot + ":" + head.toString() + ":" + target.toString())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Failed to persist attestation duty for slot {}", slot, e);
        }
    }

    public void setValidatorKey(PQKey key) {
        this.validatorKey = key;
    }

    public PQKey getValidatorKey() {
        return validatorKey;
    }

    public void performDuty() throws Exception {
        if (validatorKey == null) {
            return;
        }
        if (!dutyEnabled) {
            // API/requester node: holds the key for stakeDeposit authorization
            // but must not propose/attest (a second producer with the same key
            // double-signs each slot and forks the beacon chain).
            return;
        }
        long slot = slotService.getCurrentSlot();
        // The slot tick may run several times per slot (the tick period can be
        // shorter than pos.slotIntervalMs). Proposing/attesting more than once
        // per slot creates multiple beacons for the same slot, which the
        // slashing detector treats as a double vote. Perform duty once per
        // slot. This guard runs BEFORE any DB access so fast tick rates are
        // cheap. lastDutySlot is set AFTER the duty ran: a failed duty may be
        // retried within the slot — the persisted slashing-protection records
        // make any retry safe.
        if (slot == lastDutySlot) {
            return;
        }
        long epoch = slotService.getEpochForSlot(slot);

        boolean isProposer = false;
        BlockStoreInterface store = storeService.getStore();
        try {
            // The proposer index refers to the SELECTION SNAPSHOT list (two epochs
            // back), the same list beacon validation recomputes it from — looking
            // it up in the live set would misidentify the proposer whenever the
            // two differ (activation/exit/top-up within the lookback window).
            List<StakeRecord> validators = SlotService.selectionValidators(slot, store);
            long proposerIdx = slotService.selectProposer(slot, store);
            if (proposerIdx >= 0 && proposerIdx < validators.size()) {
                StakeRecord proposer = validators.get((int) proposerIdx);
                isProposer = java.util.Arrays.equals(proposer.getPubkey(), validatorKey.getPubKey());
            }
        } finally {
            store.close();
        }

        if (isProposer) {
            store = storeService.getStore();
            try {
                if (mayPropose(slot)) {
                    // Record BEFORE signing: a crash afterwards costs one missed
                    // slot; an unrecorded proposal followed by a crash could
                    // produce a second signed beacon for the slot (self-slash).
                    recordProposed(slot, store);
                    slotService.proposeBeaconBlock(slot, validatorKey, store);
                } else {
                    log.debug("Slashing protection: already proposed for slot {}, skipping", slot);
                }
            } finally {
                store.close();
            }
        }

        attest(slot, epoch);
        lastDutySlot = slot;
    }

    private void attest(long slot, long epoch) throws Exception {
        BlockStoreInterface store = storeService.getStore();
        try {
            Sha256Hash beaconHead = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
            if (beaconHead == null) {
                beaconHead = ghostService.getDagRoot(store);
            }
            // PoS fork choice: attest to the LMD-GHOST head (attestation-weighted
            // from the highest justified checkpoint), NOT the local longest
            // chain. If every validator attested to its own local head, votes
            // fragment across forks and 2/3 justification can never form.
            try {
                Sha256Hash ghostHead = ghostService.executeGhost(ghostService.getDagRoot(store), store);
                if (ghostHead != null) {
                    beaconHead = ghostHead;
                }
            } catch (Exception e) {
                log.debug("ghost attestation target failed, using confirmed head: {}", e.getMessage());
            }

            // CHAIN-derived attestation target. The beacon chain advances ~1
            // confirmed block per proposer slot, so the wall-clock epoch (slot/32)
            // runs FAR ahead of the chain epoch (confirmed chainlength/32) on a
            // young chain. Targeting the wall-clock epoch makes every node derive
            // a different (or non-existent) epoch-boundary checkpoint, so
            // attestations fragment and 2/3 justification never forms. Targeting
            // the CHAIN epoch (deterministic from confirmed chainlength) makes
            // all nodes agree on the same checkpoint — the source of convergence.
            long chainEpoch = SlotService.currentChainEpoch(store);
            long targetEpoch = Math.max(0, chainEpoch);

            CasperService.Checkpoint justified = casperService.getJustifiedCheckpoint();
            long sourceEpoch = justified != null ? justified.epoch : Math.max(0, targetEpoch - 1);
            Sha256Hash targetCheckpoint = casperService.ensureCheckpoint(targetEpoch, store).getBlockHash();

            // ABSTAIN when this validator's justified checkpoint is hopelessly
            // behind the chain epoch. Voting from dead state poisons quorum
            // formation for every real checkpoint (observed: sourceEpoch=0 /
            // targetEpoch=8 votes on a live chain at epoch 663k scattered the
            // vote set and froze finality mesh-wide). Ethereum semantics: a
            // validator without a usable source simply skips its slot.
            if ((justified == null && targetEpoch > 1)
                    || (justified != null && sourceEpoch < targetEpoch - ATTEST_MAX_SOURCE_LAG)) {
                log.warn("Abstaining from attestation slot {}: justifiedEpoch={} targetEpoch={} "
                        + "(catching up — will resume once synced)", slot, sourceEpoch, targetEpoch);
                return;
            }

            // Slashing protection: one attestation per slot; only a
            // byte-identical re-vote is safe after a restart.
            if (!mayAttest(slot, beaconHead, targetCheckpoint)) {
                log.warn("Slashing protection: refusing to re-attest slot {} with different content", slot);
                return;
            }
            // Record BEFORE signing (same crash-safety rationale as proposals).
            recordAttested(slot, beaconHead, targetCheckpoint, store);

            AttestationData att = new AttestationData();
            att.setSlot(slot);
            att.setEpoch(epoch);
            att.setSourceEpoch(sourceEpoch);
            att.setTargetEpoch(targetEpoch);
            att.setBeaconBlockHash(beaconHead);
            att.setSourceCheckpoint(justified != null ? justified.getBlockHash() : Sha256Hash.ZERO_HASH);
            att.setTargetCheckpoint(targetCheckpoint);
            att.setValidatorPubkey(validatorKey.getPubKey());
            // BLS key (deterministically derived from the ML-DSA seed, registered
            // in the STAKE deposit) so attestations verify against the on-chain
            // key and can be aggregated.
            att.setBlsPubkey(RandaoService.blsPubkey(validatorKey));

            // BLS signature covers the FULL attestation message (slot, epoch,
            // checkpoints, head, pubkeys), so it is verifiable end-to-end.
            byte[] sig = RandaoService.blsSign(validatorKey, att.getMessageHash().getBytes());
            att.setSignature(sig);

            casperService.processVote(att, store);

            // Durable vote propagation: publish to the attestations topic so
            // every validator consumes this vote even when gossip HTTP is
            // lossy under load (the root cause of quorum starvation).
            try {
                kafkaMessageProducer.sendAttestation(Utils.HEX.encode(att.getValidatorPubkey()),
                        Json.jsonmapper().writeValueAsBytes(att));
            } catch (Exception e) {
                log.debug("Kafka attestation publish failed: {}", e.getMessage());
            }

            // Best-effort loopback broadcast: the vote is already processed
            // locally (processVote also gossips) — a post failure must never
            // fail the duty. requester may be a comma-separated list; post to
            // each candidate with the SHORT gossip timeout so a stalled node
            // cannot block the duty thread (45-min default would).
            try {
                String requester = serverConfiguration.getRequester();
                String[] urls;
                if (requester != null && !requester.trim().isEmpty()) {
                    urls = java.util.Arrays.stream(requester.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                            .toArray(String[]::new);
                } else {
                    urls = new String[] { "http://localhost:" + serverConfiguration.getPort() };
                }
                for (int i = 0; i < urls.length; i++) {
                    urls[i] = urls[i].endsWith("/") ? urls[i] + ReqCmd.submitAttestation.name()
                            : urls[i] + "/" + ReqCmd.submitAttestation.name();
                }
                OkHttp3Util.postGossip(urls, Json.jsonmapper().writeValueAsBytes(att));
            } catch (Exception e) {
                log.debug("Loopback attestation post failed: {}", e.getMessage());
            }
        } finally {
            store.close();
        }
    }
}
