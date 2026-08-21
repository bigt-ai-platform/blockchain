package net.bigtangle.bridge;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;

/**
 * Layer-1 consensus validation for CROSSTANGLE blocks. On an L1 chain every
 * CROSSTANGLE block carries value (wrapped issuance, peg messages), so the
 * legacy {@code case BLOCKTYPE_CROSSTANGLE: break} no-op would leave an
 * unauthenticated zero-input mint as valid consensus.
 *
 * <p>Rules:
 * <ul>
 * <li>a transaction with inputs must conserve value PER TOKEN (no cross-token
 *     mint), correctly spend its connected UTXOs (ownership proof) and must not
 *     spend a bonded stake output,</li>
 * <li>a zero-input transaction may ONLY mint when it is an AUTHENTICATED bridge
 *     issuance: {@code dataClassName == IssueWrappedToken}, declaring this
 *     chain's id, signed by the chain's configured issuance key
 *     ({@code bridge.issuancePubKeyHex} — a dedicated key, never the L0 vault
 *     key). Data-only messages (e.g. a locally created anchor) carry no outputs
 *     and are not a mint.</li>
 * <li>a lock may only be issued ONCE: the same signed issuance wrapped in a
 *     second block is rejected at CONFIRMATION (chain-derived issued-lock
 *     table), so a replay cannot inflate the wrapped supply.</li>
 * </ul>
 */
public class L1CrosstangleHandler implements BlockTypeHandler {

    public static final String ISSUE_WRAPPED_TOKEN_DATA_CLASS = "IssueWrappedToken";

    /** Chain-derived issued-lock table (pos_state): a lock may be issued at most once. */
    private static final String ISSUED_LOCK_SERVICE = "pos";
    private static final String ISSUED_LOCK_PREFIX = "issuedlock_";

    private static final Logger logger = LoggerFactory.getLogger(L1CrosstangleHandler.class);

    private final BridgeConfiguration bridgeConfiguration;
    private final NetworkParameters networkParameters;

    public L1CrosstangleHandler(BridgeConfiguration bridgeConfiguration, NetworkParameters networkParameters) {
        this.bridgeConfiguration = bridgeConfiguration;
        this.networkParameters = networkParameters;
    }

    @Override
    public SolidityState checkFull(SolidityContext ctx) {
        try {
            validateCrosstangle(ctx.block(), ctx.store());
            return SolidityState.getSuccessState();
        } catch (Exception e) {
            logger.warn("L1 CROSSTANGLE validation failed for {}: {}", ctx.blockHash(), e.getMessage());
            return SolidityState.getFailState();
        }
    }

    @Override
    public SolidityState checkFormal(SolidityContext ctx) {
        if (ctx.block() == null || ctx.block().getTransactions() == null
                || ctx.block().getTransactions().isEmpty()) {
            return SolidityState.getFailState();
        }
        return SolidityState.getSuccessState();
    }

    /**
     * Pre-confirm replay guard (R3): a byte-identical copy of a valid signed
     * issuance wrapped in a new block re-validates in checkFull (signature and
     * chain id still hold), so the SAME L0 lock must be rejected at the
     * consensus level — at CONFIRMATION, from chain-derived state, never in
     * checkFull (a node's local view can legitimately lag the confirmed chain).
     *
     * <p>Reads the issued-lock table written by prior confirmations; if the lock
     * is already recorded by a DIFFERENT block this is a replay and the block is
     * vetoed (marked invalid, outputs never confirm). Deterministic and
     * chain-position-aligned.
     */
    @Override
    public boolean checkPreConfirm(SolidityContext ctx) throws BlockStoreException {
        if (!ctx.confirmation()) {
            return true;
        }
        for (Transaction tx : ctx.block().getTransactions()) {
            String lock = issuanceLock(tx);
            if (lock == null) {
                continue;
            }
            byte[] existing = ctx.store().getPosState(ISSUED_LOCK_SERVICE, ISSUED_LOCK_PREFIX + lock);
            if (existing != null && !java.util.Arrays.equals(existing, ctx.blockHash().getBytes())) {
                logger.warn("L1 CROSSTANGLE issuance replay rejected for lock {} already issued by {}",
                        lock, Sha256Hash.wrap(existing));
                return false;
            }
        }
        return true;
    }

    /**
     * Records the chain-derived issued-lock state at confirmation (a lock is
     * minted at most once) and rolls it back on unconfirm so a reorg that
     * unconfirms an issuance frees the lock consistently.
     */
    @Override
    public void confirm(SolidityContext ctx) throws BlockStoreException {
        ServiceBaseConfirmation.queueBlockEvaluation(ctx.blockHash(), ctx.chainlength(), ctx.confirmation(),
                ctx.store());
        for (Transaction tx : ctx.block().getTransactions()) {
            String lock = issuanceLock(tx);
            if (lock == null) {
                continue;
            }
            String key = ISSUED_LOCK_PREFIX + lock;
            if (ctx.confirmation()) {
                byte[] existing = ctx.store().getPosState(ISSUED_LOCK_SERVICE, key);
                if (existing == null || !java.util.Arrays.equals(existing, ctx.blockHash().getBytes())) {
                    ctx.store().savePosState(ISSUED_LOCK_SERVICE, key, ctx.blockHash().getBytes());
                }
            } else {
                byte[] existing = ctx.store().getPosState(ISSUED_LOCK_SERVICE, key);
                if (existing != null && java.util.Arrays.equals(existing, ctx.blockHash().getBytes())) {
                    ctx.store().deletePosState(ISSUED_LOCK_SERVICE, key);
                }
            }
        }
    }

    private void validateCrosstangle(Block block, BlockStoreInterface store) throws Exception {
        for (Transaction tx : block.getTransactions()) {
            if (tx.getInputs() == null || tx.getInputs().isEmpty()) {
                validateIssuance(tx);
            } else {
                validateConservation(tx, store);
            }
        }
    }

    /**
     * A zero-input transaction creates value from nothing, so it is only valid
     * as an AUTHENTICATED bridge issuance: it must declare this chain's id and
     * carry a signature by the chain's dedicated issuance key over the
     * transaction hash (which covers the outputs, the declared chain id and the
     * L0 lock reference). Data-only messages (no outputs) are not a mint and
     * pass.
     */
    private void validateIssuance(Transaction tx) throws Exception {
        if (tx.getOutputs() == null || tx.getOutputs().isEmpty()) {
            return;
        }
        if (!ISSUE_WRAPPED_TOKEN_DATA_CLASS.equals(tx.getDataClassName()) || tx.getData() == null) {
            throw new BlockStoreException(
                    "L1 CROSSTANGLE zero-input tx creates value without authenticated bridge issuance");
        }
        // R4: a DEDICATED issuance key, never the L0 vault key — the vault key
        // must only live on L0 (it signs peg-out releases).
        String issuancePubKeyHex = bridgeConfiguration.getIssuancePubKeyHex();
        if (issuancePubKeyHex == null || issuancePubKeyHex.isEmpty()) {
            throw new BlockStoreException(
                    "L1 CROSSTANGLE issuance cannot be authenticated: bridge.issuancePubKeyHex is not configured");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
        Object declaredChain = data.get("chainId");
        if (declaredChain == null || !networkParameters.getChainId().equals(declaredChain.toString())) {
            throw new BlockStoreException("L1 CROSSTANGLE issuance declares chain " + declaredChain
                    + " but this is chain " + networkParameters.getChainId());
        }
        byte[] signature = tx.getDataSignature();
        if (signature == null || signature.length == 0) {
            throw new BlockStoreException("L1 CROSSTANGLE issuance is not signed");
        }
        PQKey issuanceKey = PQKey.fromPublicOnly(Utils.HEX.decode(issuancePubKeyHex));
        if (!PQScriptUtils.verifyPQ(issuanceKey.getPublicKeyBytes(), signature, tx.getHash())) {
            throw new BlockStoreException(
                    "L1 CROSSTANGLE issuance signature does not verify under the chain's issuance key");
        }
    }

    /**
     * The consensus dedup key of an issuance transaction ({@code chainId:
     * lockBlockHash:lockIndex}), or null when the tx is not a lock-backed
     * issuance. The signature covers this data (it is inside tx.getHash()), so
     * a forger cannot alter the declared lock reference.
     */
    private String issuanceLock(Transaction tx) {
        if (tx.getInputs() != null && !tx.getInputs().isEmpty()) {
            return null;
        }
        if (!ISSUE_WRAPPED_TOKEN_DATA_CLASS.equals(tx.getDataClassName()) || tx.getData() == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
            Object chainId = data.get("chainId");
            Object lockBlockHash = data.get("lockBlockHash");
            Object lockIndex = data.get("lockIndex");
            if (chainId == null || lockBlockHash == null || lockIndex == null) {
                return null;
            }
            return chainId + ":" + lockBlockHash + ":" + lockIndex;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A transaction with inputs must conserve value PER TOKEN and prove
     * ownership of every input (no cross-token mint, no spending of another
     * owner's funds, no moving of bonded stake).
     */
    private void validateConservation(Transaction tx, BlockStoreInterface store) throws Exception {
        Map<String, Coin> valueIn = new HashMap<>();
        Map<String, Coin> valueOut = new HashMap<>();
        for (int j = 0; j < tx.getInputs().size(); j++) {
            TransactionInput in = tx.getInputs().get(j);
            UTXO prevOut = store.getTransactionOutput(in.getOutpoint().getBlockHash(),
                    in.getOutpoint().getTxHash(), in.getOutpoint().getIndex());
            if (prevOut == null) {
                throw new BlockStoreException("L1 CROSSTANGLE input UTXO not found: " + in.getOutpoint());
            }
            if (prevOut.getScript() == null || in.getScriptSig() == null) {
                throw new BlockStoreException("L1 CROSSTANGLE input has no script or scriptSig");
            }
            // Ownership proof: only the UTXO's owner can produce a valid scriptSig.
            in.getScriptSig().correctlySpends(tx, j, prevOut.getScript(), Script.ALL_VERIFY_FLAGS);
            // Bonded stake outputs are locked until a withdrawal mechanism exists.
            if (in.getOutpoint().getIndex() == 0) {
                StakeRecord bonded = store.getStakeDepositByOutput(
                        in.getOutpoint().getBlockHash(), in.getOutpoint().getTxHash());
                if (bonded != null) {
                    throw new BlockStoreException(
                            "L1 CROSSTANGLE cannot spend a bonded stake output: " + in.getOutpoint());
                }
            }
            Coin coin = prevOut.getValue();
            valueIn.merge(Utils.HEX.encode(coin.getTokenid()), coin, Coin::add);
        }
        for (TransactionOutput out : tx.getOutputs()) {
            Coin coin = out.getValue();
            if (coin.signum() < 0) {
                throw new BlockStoreException("L1 CROSSTANGLE output value is negative");
            }
            valueOut.merge(Utils.HEX.encode(coin.getTokenid()), coin, Coin::add);
        }
        // PER-TOKEN conservation: summing raw amounts across tokens would let a
        // tx spend its own 100 BIG and mint 100 units of ANY other token.
        for (Map.Entry<String, Coin> entry : valueOut.entrySet()) {
            Coin inCoin = valueIn.get(entry.getKey());
            if (inCoin == null || inCoin.compareTo(entry.getValue()) != 0) {
                throw new BlockStoreException("L1 CROSSTANGLE tx does not conserve value for token "
                        + entry.getKey() + ": in=" + (inCoin == null ? 0 : inCoin.getValue())
                        + " out=" + entry.getValue().getValue());
            }
        }
        for (Map.Entry<String, Coin> entry : valueIn.entrySet()) {
            Coin outCoin = valueOut.get(entry.getKey());
            if (outCoin == null || outCoin.compareTo(entry.getValue()) != 0) {
                throw new BlockStoreException("L1 CROSSTANGLE tx does not conserve value for token "
                        + entry.getKey());
            }
        }
    }
}
