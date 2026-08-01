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

    @Autowired
    private SlotService slotService;

    @Autowired
    private CasperService casperService;

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

    private PQKey validatorKey;

    @PostConstruct
    public void init() {
        if (configuredValidatorKey != null && !configuredValidatorKey.isEmpty()) {
            try {
                this.validatorKey = PQKey.fromPrivateKeyHex(configuredValidatorKey);
            } catch (Exception e) {
                log.warn("Invalid pos.validatorKey config (expected 64-hex ML-DSA-only or 128-hex dual seed): {}", e.getMessage());
            }
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
        long slot = slotService.getCurrentSlot();
        long epoch = slotService.getEpochForSlot(slot);

        boolean isProposer = false;
        BlockStoreInterface store = storeService.getStore();
        try {
            List<StakeRecord> validators = store.getActiveStakeDeposits();
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
                slotService.proposeBeaconBlock(slot, store);
            } finally {
                store.close();
            }
        }

        attest(slot, epoch);
    }

    private void attest(long slot, long epoch) throws Exception {
        BlockStoreInterface store = storeService.getStore();
        try {
            Sha256Hash beaconHead = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
            if (beaconHead == null) {
                beaconHead = ghostService.getDagRoot(store);
            }

            AttestationData att = new AttestationData();
            att.setSlot(slot);
            att.setEpoch(epoch);
            att.setBeaconBlockHash(beaconHead);
            att.setValidatorPubkey(validatorKey.getPubKey());

            Sha256Hash msgHash = Sha256Hash.twiceOf(
                    (slot + ":" + beaconHead.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] sig = validatorKey.sign(msgHash).serialize();
            att.setSignature(sig);

            casperService.processVote(att, store);

            String requester = serverConfiguration.getRequester();
            String contextRoot = requester != null && !requester.isEmpty() ? requester
                    : "http://localhost:" + serverConfiguration.getPort() + "/";
            if (!contextRoot.endsWith("/")) contextRoot += "/";
            OkHttp3Util.post(contextRoot + ReqCmd.submitAttestation.name(),
                    Json.jsonmapper().writeValueAsBytes(att));
        } finally {
            store.close();
        }
    }
}
