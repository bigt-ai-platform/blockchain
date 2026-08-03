package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.UTXO;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.wallet.Wallet;

/**
 * Verifies the L1-order chain keeps confirming blocks across an epoch-reward
 * boundary.
 *
 * <p>EpochRewardService.distributeEpochRewards creates a BLOCKTYPE_BEACON
 * reward block at each epoch boundary when the fee pool is non-empty. If that
 * reward block lacks a RewardInfo-bearing first transaction,
 * BlockStoreService.saveChainConnected throws a NullPointerException and the
 * chain stops confirming (funding TRANSFER blocks stay unconfirmed forever).
 *
 * <p>This test runs against L1 (like RemoteOrderTests), generates fees so the
 * reward block is produced at the next epoch boundary, then submits a fresh
 * payment and asserts it CONFIRMS — proving the reward block did not stall the
 * chain.
 */
public class RemoteEpochRewardTests extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteEpochRewardTests.class);

    public RemoteEpochRewardTests() {
        // L1-order server is where the epoch reward block is created and where
        // the chain-confirmation NPE manifests.
        contextRoot = l1Url;
    }

    @Test
    public void testChainConfirmsAcrossEpochReward() throws Exception {
        // ============================================================
        // 1. Generate fees so the epoch reward block will be produced.
        // ============================================================
        log.info("Funding a key on L1 to accumulate fees...");
        PQKey funded = PQKey.createNew();
        payBigTo(funded, Coin.FEE_DEFAULT.getValue().multiply(java.math.BigInteger.valueOf(100)), null);
        Thread.sleep(5000);

        // ============================================================
        // 2. Wait until the next epoch boundary has passed (the reward
        //    block is created by SlotService.processEpoch at the first
        //    slot tick after an epoch change).
        // ============================================================
        long epochMs = 12_000L * 32L; // SLOT_DURATION_MS * SLOTS_PER_EPOCH
        long origin = 1532896109000L;
        long now = System.currentTimeMillis();
        long currentEpoch = (now - origin) / epochMs;
        long nextBoundaryMs = (currentEpoch + 1) * epochMs + origin + 2_000L; // margin for the tick
        long waitMs = nextBoundaryMs - System.currentTimeMillis();
        log.info("Waiting {} ms for next epoch boundary...", waitMs);
        if (waitMs > 0) {
            Thread.sleep(waitMs);
        }

        // Give the slot tick time to run processEpoch and the reward block
        // to enter the chain queue / confirmation pipeline.
        Thread.sleep(15_000);

        // ============================================================
        // 3. Verify the chain still confirms: pay a fresh key and wait
        //    for its BC balance to appear (confirmed=true).
        // ============================================================
        log.info("Submitting post-reward payment to verify chain still confirms...");
        PQKey recipient = PQKey.createNew();
        payBigTo(recipient, Coin.FEE_DEFAULT.getValue(), null);

        boolean confirmed = false;
        for (int i = 0; i < 60 && !confirmed; i++) {
            List<UTXO> utxos = getBalance(false, recipient);
            for (UTXO u : utxos) {
                if (Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, u.getValue().getTokenid())
                        && u.getValue().getValue().signum() > 0) {
                    confirmed = true;
                    break;
                }
            }
            if (!confirmed) {
                Thread.sleep(2000);
            }
        }
        assertTrue(confirmed, "Post-reward payment should confirm — the epoch reward block stalled the chain");
        log.info("Chain confirmed a new block after the epoch reward boundary: PASS");
    }
}
