package net.bigtangle.server.layer0.handler;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.bridge.AnchorService;
import net.bigtangle.bridge.BridgeService;
import net.bigtangle.bridge.LayerAnchor;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.script.Script;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.store.BlockStoreInterface;

public class L0AnchorHandler implements BlockTypeHandler {

    private static final Logger logger = LoggerFactory.getLogger(L0AnchorHandler.class);

    private final AnchorService anchorService;
    private final BridgeService bridgeService;

    public L0AnchorHandler(AnchorService anchorService, BridgeService bridgeService) {
        this.anchorService = anchorService;
        this.bridgeService = bridgeService;
    }

    @Override
    public SolidityState checkFull(SolidityContext ctx) {
        try {
            validateCrosstangle(ctx.block(), ctx.store());
            return SolidityState.getSuccessState();
        } catch (Exception e) {
            logger.warn("CROSSTANGLE validation failed for {}: {}", ctx.blockHash(), e.getMessage());
            return SolidityState.getFailState();
        }
    }

    @Override
    public SolidityState checkFormal(SolidityContext ctx) {
        // Structural check: every CROSSTANGLE tx must have at least one
        // transaction and no zero-input value creation (checked in checkFull).
        if (ctx.block() == null || ctx.block().getTransactions() == null
                || ctx.block().getTransactions().isEmpty()) {
            return SolidityState.getFailState();
        }
        return SolidityState.getSuccessState();
    }

    /**
     * Real consensus validation for CROSSTANGLE blocks — the cross-chain message
     * type carries value, so it is NOT exempt:
     * <ul>
     * <li>every input's scriptSig must correctly spend its connected UTXO
     *     (ownership proof — no locking/releasing someone else's funds),</li>
     * <li>no input may spend a bonded stake output (validators cannot move
     *     locked bond via a cross-chain message),</li>
     * <li>value must be conserved PER TOKEN (inputs == outputs for every token
     *     id; no zero-input mint of arbitrary value),</li>
     * <li>an embedded {@link LayerAnchor} (if any) must pass
     *     {@link AnchorService#validateAnchor} (signature, SPV proof, burn).</li>
     * </ul>
     */
    private void validateCrosstangle(Block block, BlockStoreInterface store) throws Exception {
        for (Transaction tx : block.getTransactions()) {
            Map<String, Coin> valueIn = new HashMap<>();
            Map<String, Coin> valueOut = new HashMap<>();
            for (int j = 0; j < tx.getInputs().size(); j++) {
                TransactionInput in = tx.getInputs().get(j);
                UTXO prevOut = store.getTransactionOutput(in.getOutpoint().getBlockHash(),
                        in.getOutpoint().getTxHash(), in.getOutpoint().getIndex());
                if (prevOut == null) {
                    throw new BlockStoreException("CROSSTANGLE input UTXO not found: " + in.getOutpoint());
                }
                if (prevOut.getScript() == null || in.getScriptSig() == null) {
                    throw new BlockStoreException("CROSSTANGLE input has no script or scriptSig");
                }
                // Ownership proof: only the UTXO's owner can produce a valid scriptSig.
                in.getScriptSig().correctlySpends(tx, j, prevOut.getScript(), Script.ALL_VERIFY_FLAGS);
                // Bonded stake outputs are locked until a withdrawal mechanism
                // exists; a cross-chain message must not be able to move them.
                if (in.getOutpoint().getIndex() == 0) {
                    StakeRecord bonded = store.getStakeDepositByOutput(
                            in.getOutpoint().getBlockHash(), in.getOutpoint().getTxHash());
                    if (bonded != null) {
                        throw new BlockStoreException(
                                "CROSSTANGLE cannot spend a bonded stake output: " + in.getOutpoint());
                    }
                }
                Coin coin = prevOut.getValue();
                valueIn.merge(Utils.HEX.encode(coin.getTokenid()), coin, Coin::add);
            }
            for (TransactionOutput out : tx.getOutputs()) {
                Coin coin = out.getValue();
                if (coin.signum() < 0) {
                    throw new BlockStoreException("CROSSTANGLE output value is negative");
                }
                valueOut.merge(Utils.HEX.encode(coin.getTokenid()), coin, Coin::add);
            }
            // No zero-input value creation (the unauthenticated arbitrary-mint vector).
            if (tx.getInputs().isEmpty() && valueOut.values().stream().anyMatch(c -> c.signum() > 0)) {
                throw new BlockStoreException("CROSSTANGLE tx creates value from nothing");
            }
            // PER-TOKEN value conservation: a cross-chain message moves value, it
            // does not mint it. Summing raw amounts across tokens would let a tx
            // spend its own 100 BIG and mint 100 units of ANY other token.
            for (Map.Entry<String, Coin> entry : valueOut.entrySet()) {
                Coin inCoin = valueIn.get(entry.getKey());
                if (inCoin == null || inCoin.compareTo(entry.getValue()) != 0) {
                    throw new BlockStoreException("CROSSTANGLE tx does not conserve value for token "
                            + entry.getKey() + ": in=" + (inCoin == null ? 0 : inCoin.getValue())
                            + " out=" + entry.getValue().getValue());
                }
            }
            for (Map.Entry<String, Coin> entry : valueIn.entrySet()) {
                Coin outCoin = valueOut.get(entry.getKey());
                if (outCoin == null || outCoin.compareTo(entry.getValue()) != 0) {
                    throw new BlockStoreException("CROSSTANGLE tx does not conserve value for token "
                            + entry.getKey() + ": in=" + entry.getValue().getValue()
                            + " out=" + (outCoin == null ? 0 : outCoin.getValue()));
                }
            }
        }
        for (Transaction tx : block.getTransactions()) {
            if ("LayerAnchor".equals(tx.getDataClassName()) && tx.getData() != null) {
                LayerAnchor anchor = LayerAnchor.parseCanonical(tx.getData());
                anchorService.validateAnchor(anchor);
                break;
            }
        }
    }

    @Override
    public void confirm(SolidityContext ctx) throws BlockStoreException {
        ctx.store().updateBlockEvaluationConfirmed(ctx.blockHash(), ctx.confirmation());
        ctx.store().updateBlockEvaluationChainlength(ctx.blockHash(), ctx.chainlength());
        try {
            if (ctx.confirmation()) {
                anchorService.processReceivedAnchor(ctx.block(), ctx.store());
            }
            anchorService.confirmAnchor(ctx.block(), ctx.confirmation(), ctx.store());
            if (ctx.confirmation()) {
                // Settle the peg-out ONLY on actual chain confirmation — not at
                // save time — so a reorged (unconfirmed) anchor can never release
                // vault funds. processPegOut is a no-op when this node holds no
                // matching vault.
                AnchorRecord rec = ctx.store().getAnchorByBlockHash(ctx.blockHash());
                if (rec != null && rec.isConfirmed() && bridgeService != null) {
                    bridgeService.processPegOut(rec, ctx.store());
                }
            }
        } catch (BlockStoreException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to process anchor at confirmation", e);
        }
    }

    @Override
    public void connect(SolidityContext ctx) {
    }
}
