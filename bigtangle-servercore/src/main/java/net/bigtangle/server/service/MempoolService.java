package net.bigtangle.server.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.script.Script;
import net.bigtangle.core.ContractEventInfo;
import net.bigtangle.core.EVMTransactionInfo;
import net.bigtangle.core.OrderCancelInfo;
import net.bigtangle.core.OrderOpenInfo;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UserSettingDataInfo;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class MempoolService {

    private static final Logger log = LoggerFactory.getLogger(MempoolService.class);

    @Autowired
    private StoreService storeService;

    @Autowired
    private NetworkParameters networkParameters;

    private final ConcurrentLinkedQueue<Transaction> pendingTxns = new ConcurrentLinkedQueue<>();

    private final Map<BlockType, ConcurrentLinkedQueue<Transaction>> pendingTxnsByType = new EnumMap<>(
            BlockType.class);

    private final AtomicInteger totalSubmitted = new AtomicInteger(0);

    private final ConcurrentHashMap<TransactionOutPoint, Transaction> spentOutpoints = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Transaction, Set<TransactionOutPoint>> txOutpoints = new ConcurrentHashMap<>();

    public void submit(Block block) {
        BlockType blockType = block.getBlockType();
        List<Transaction> txs = block.getTransactions();
        if (txs != null) {
            ConcurrentLinkedQueue<Transaction> typeQueue = pendingTxnsByType
                    .computeIfAbsent(blockType, k -> new ConcurrentLinkedQueue<>());
            for (Transaction tx : txs) {
                checkAndAdd(tx);
                pendingTxns.add(tx);
                typeQueue.add(tx);
            }
            totalSubmitted.addAndGet(txs.size());
        }
    }

    public void submitTransaction(Transaction tx) {
        checkAndAdd(tx);
        BlockType blockType = getTransactionType(tx);
        ConcurrentLinkedQueue<Transaction> typeQueue = pendingTxnsByType
                .computeIfAbsent(blockType, k -> new ConcurrentLinkedQueue<>());
        pendingTxns.add(tx);
        typeQueue.add(tx);
        totalSubmitted.incrementAndGet();
    }

    /**
     * Submits an order transaction (buy/sell/cancel) into the typed mempool
     * queue for its order type (ORDER_OPEN / ORDER_CANCEL). Orders are
     * transactions only — no block is created here; block assembly drains the
     * typed queues via {@link #drainAllByType()}.
     *
     * @throws VerificationException if the transaction is not an order
     */
    public void submitOrder(Transaction tx) {
        BlockType blockType = getTransactionType(tx);
        if (blockType != BlockType.BLOCKTYPE_ORDER_OPEN && blockType != BlockType.BLOCKTYPE_ORDER_CANCEL) {
            throw new VerificationException("Not an order transaction: " + tx.getDataClassName());
        }
        submitTransaction(tx);
    }

    /** Snapshot of the transactions currently pending for a block type. */
    public List<Transaction> getPendingByType(BlockType blockType) {
        ConcurrentLinkedQueue<Transaction> queue = pendingTxnsByType.get(blockType);
        return queue == null ? Collections.emptyList() : new ArrayList<>(queue);
    }

    /** Number of transactions currently pending for a block type. */
    public int countByType(BlockType blockType) {
        ConcurrentLinkedQueue<Transaction> queue = pendingTxnsByType.get(blockType);
        return queue == null ? 0 : queue.size();
    }

    private void checkAndAdd(Transaction tx) {
        List<TransactionInput> inputs = tx.getInputs();
        Set<TransactionOutPoint> outpoints = new HashSet<>();
        if (inputs != null) {
            for (TransactionInput in : inputs) {
                TransactionOutPoint outpoint = in.getOutpoint();
                if (outpoint == null || outpoint.isCoinBase()) {
                    continue;
                }
                if (spentOutpoints.containsKey(outpoint)) {
                    throw new VerificationException.ConflictPossibleException(
                            "Mempool double-spend: outpoint " + outpoint + " already spent by pending tx");
                }
                outpoints.add(outpoint);
            }
        }
        // Complete full validation (structural, UTXO existence, value
        // conservation, fee, script and type-specific data) so every
        // transaction accepted into the mempool is solid when batched into a
        // block — an invalid tx must be rejected at submission, not produce a
        // silently-invalid (solid=-1) block later.
        verifyTransaction(tx);
        for (TransactionOutPoint outpoint : outpoints) {
            spentOutpoints.put(outpoint, tx);
        }
        if (!outpoints.isEmpty()) {
            txOutpoints.put(tx, outpoints);
        }
    }

    private void verifyTransaction(Transaction tx) {
        tx.verify();
        // Coinbase-like transactions mint value by protocol design; their value
        // conservation is validated by the reward solidity checks, not here.
        if (tx.isCoinBase()) {
            return;
        }
        List<TransactionInput> inputs = tx.getInputs();

        Map<String, net.bigtangle.core.Coin> valueIn = new HashMap<>();
        Map<String, net.bigtangle.core.Coin> valueOut = new HashMap<>();

        for (TransactionOutput out : tx.getOutputs()) {
            String tokenKey = Utils.HEX.encode(out.getValue().getTokenid());
            if (valueOut.containsKey(tokenKey)) {
                valueOut.put(tokenKey, valueOut.get(tokenKey).add(out.getValue()));
            } else {
                valueOut.put(tokenKey, out.getValue());
            }
            if (out.getValue().signum() < 0) {
                throw new VerificationException.InvalidTransactionException(
                        "Transaction output value negative");
            }
        }

        // Fetch and verify each spendable (non-coinbase) input against its UTXO.
        boolean hasSpendableInput = false;
        if (inputs != null) {
            for (TransactionInput in : inputs) {
                if (in.getOutpoint() != null && !in.getOutpoint().isCoinBase()) {
                    hasSpendableInput = true;
                    break;
                }
            }
        }
        if (hasSpendableInput) {
            BlockStoreInterface store;
            try {
                store = storeService.getStore();
            } catch (Exception e) {
                throw new VerificationException("Mempool: cannot open store for verification");
            }
            try {
                for (int index = 0; index < inputs.size(); index++) {
                    TransactionInput in = inputs.get(index);
                    TransactionOutPoint outpoint = in.getOutpoint();
                    if (outpoint == null || outpoint.isCoinBase()) {
                        continue;
                    }
                    UTXO utxo = store.getTransactionOutput(outpoint.getBlockHash(),
                            outpoint.getTxHash(), outpoint.getIndex());
                    if (utxo == null) {
                        throw new VerificationException("Mempool: UTXO not found for " + outpoint);
                    }
                    String tokenKey = Utils.HEX.encode(utxo.getValue().getTokenid());
                    if (valueIn.containsKey(tokenKey)) {
                        valueIn.put(tokenKey, valueIn.get(tokenKey).add(utxo.getValue()));
                    } else {
                        valueIn.put(tokenKey, utxo.getValue());
                    }
                    Script scriptPubKey = utxo.getScript();
                    if (scriptPubKey == null) {
                        throw new VerificationException("Mempool: no scriptPubKey for UTXO " + outpoint);
                    }
                    in.getScriptSig().correctlySpends(tx, index, scriptPubKey, Script.ALL_VERIFY_FLAGS);
                }
            } catch (VerificationException e) {
                throw e;
            } catch (Exception e) {
                throw new VerificationException("Mempool transaction verification failed: " + e.getMessage());
            } finally {
                try {
                    store.close();
                } catch (Exception e) {
                    log.warn("Error closing store in mempool verification", e);
                }
            }
        }

        // Value conservation: every output token must be backed by an input of
        // the same token. Runs for every transaction (even empty-input ones), so
        // a tx that creates value from nothing is rejected here exactly as the
        // block-level checkTxInputOutput would.
        boolean feePaid = false;
        for (Map.Entry<String, net.bigtangle.core.Coin> entry : valueOut.entrySet()) {
            net.bigtangle.core.Coin inVal = valueIn.get(entry.getKey());
            if (inVal == null) {
                throw new VerificationException.InvalidTransactionException(
                        "Transaction input and output values do not match");
            }
            if (entry.getValue().isBIG() && !feePaid) {
                if (inVal.compareTo(entry.getValue().add(net.bigtangle.core.Coin.FEE_DEFAULT)) >= 0) {
                    feePaid = true;
                }
            }
            if (inVal.compareTo(entry.getValue()) < 0) {
                throw new VerificationException.InvalidTransactionException(
                        "Transaction input and output values do not match");
            }
        }
        if (!feePaid) {
            net.bigtangle.core.Coin bigInput = valueIn.get(NetworkParameters.BIGTANGLE_TOKENID_STRING);
            net.bigtangle.core.Coin bigOutput = valueOut.get(NetworkParameters.BIGTANGLE_TOKENID_STRING);
            if (bigOutput == null && bigInput != null
                    && bigInput.compareTo(net.bigtangle.core.Coin.FEE_DEFAULT) >= 0) {
                feePaid = true;
            }
        }
        if (!feePaid && valueIn.containsKey(NetworkParameters.BIGTANGLE_TOKENID_STRING)) {
            throw new VerificationException.NoFeeException(net.bigtangle.core.Coin.FEE_DEFAULT.toString());
        }

        // Type-specific data validation mirroring the block-level
        // checkFullTypeSpecificSolidity so a mempool-accepted tx is guaranteed
        // solid (rejects, e.g., malformed orders instead of producing a
        // silently-invalid (solid=-1) block later).
        verifyTransactionData(tx);
    }

    private void verifyTransactionData(Transaction tx) {
        String dataClassName = tx.getDataClassName();
        if (dataClassName == null || tx.getData() == null) {
            return;
        }
        switch (dataClassName) {
        case "OrderOpen":
            verifyOrderOpenData(tx);
            break;
        case "OrderCancelInfo":
            verifyOrderCancelData(tx);
            break;
        case "ContractEventInfo":
            verifyContractEventData(tx);
            break;
        case "EVMTransactionInfo":
            verifyEVMData(tx);
            break;
        case "UserSettingDataInfo":
            verifyUserSettingData(tx);
            break;
        default:
            break;
        }
    }

    /** Mirrors {@code checkFormalOrderOpenSolidity} for a single order tx. */
    private void verifyOrderOpenData(Transaction tx) {
        try {
            OrderOpenInfo info = new OrderOpenInfo().parse(tx.getData());
            if (info.getTargetTokenid() == null) {
                throw new VerificationException.InvalidTransactionDataException("Invalid target tokenid");
            }
            if (info.getTargetValue() < 1 || info.getTargetValue() > Long.MAX_VALUE) {
                throw new VerificationException.InvalidTransactionDataException("Invalid target value");
            }
            long from = info.getValidFromTime() == null ? 0 : info.getValidFromTime();
            long to = info.getValidToTime() == null ? 0 : info.getValidToTime();
            if (to > Math.addExact(from, NetworkParameters.ORDER_TIMEOUT_MAX)) {
                throw new VerificationException.InvalidTransactionException(
                        "Order timeout exceeds maximum of " + NetworkParameters.ORDER_TIMEOUT_MAX
                                + " seconds (validFrom=" + from + ", validTo=" + to + ")");
            }
            if (!PQKey.fromPublicOnly(info.getBeneficiaryPubKey()).toAddress(networkParameters).toBase58()
                    .equals(info.getBeneficiaryAddress())) {
                throw new VerificationException.InvalidOrderException(
                        "The address does not match with the given pubkey.");
            }
        } catch (VerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new VerificationException("Mempool: malformed order transaction data: " + e.getMessage());
        }
    }

    /** Mirrors {@code checkFormalOrderOpSolidity} for a single cancel tx. */
    private void verifyOrderCancelData(Transaction tx) {
        if (!tx.getOutputs().isEmpty()) {
            throw new VerificationException.TransactionOutputsDisallowedException();
        }
        OrderCancelInfo info;
        try {
            info = new OrderCancelInfo().parse(tx.getData());
        } catch (IOException e) {
            throw new VerificationException.MalformedTransactionDataException();
        }
        if (info.getBlockHash() == null) {
            throw new VerificationException.InvalidTransactionDataException("Invalid target txhash");
        }
    }

    /** Mirrors {@code checkFormalContractEventSolidity} for a single event tx. */
    private void verifyContractEventData(Transaction tx) {
        ContractEventInfo info;
        try {
            info = new ContractEventInfo().parse(tx.getData());
        } catch (IOException e) {
            throw new VerificationException.MalformedTransactionDataException();
        }
        if (info.getContractTokenid() == null) {
            throw new VerificationException.InvalidTransactionDataException("Invalid contract tokenid");
        }
    }

    /** Mirrors {@code checkFullEVMTransactionSolidity}'s store-free checks. */
    private void verifyEVMData(Transaction tx) {
        EVMTransactionInfo info;
        try {
            info = new EVMTransactionInfo().parse(tx.getData());
        } catch (IOException e) {
            throw new VerificationException.MalformedTransactionDataException();
        }
        if (info.getContractTokenid() == null || info.getFromAddress() == null) {
            throw new VerificationException.InvalidTransactionDataException(
                    "Invalid EVM contract tokenid or sender");
        }
        if (info.getValue() == null || info.getGasPrice() == null) {
            throw new VerificationException.InvalidTransactionDataException("EVM value and gasPrice are required");
        }
        if (info.getGasLimit() <= 0) {
            throw new VerificationException.InvalidTransactionDataException("Invalid EVM gas limit");
        }
        if (info.getValue().signum() < 0) {
            throw new VerificationException.InvalidTransactionDataException("Invalid EVM value");
        }
    }

    /** Ensures the user-setting payload parses. */
    private void verifyUserSettingData(Transaction tx) {
        try {
            new UserSettingDataInfo().parse(tx.getData());
        } catch (IOException e) {
            throw new VerificationException.MalformedTransactionDataException();
        }
    }

    public List<Transaction> drainAll() {
        List<Transaction> batch = new ArrayList<>();
        Transaction tx;
        while ((tx = pendingTxns.poll()) != null) {
            removeOutpoints(tx);
            batch.add(tx);
        }
        for (ConcurrentLinkedQueue<Transaction> queue : pendingTxnsByType.values()) {
            queue.clear();
        }
        return batch;
    }

    public Map<BlockType, List<Transaction>> drainAllByType() {
        Map<BlockType, List<Transaction>> result = new EnumMap<>(BlockType.class);
        for (Transaction tx : pendingTxns) {
            removeOutpoints(tx);
        }
        pendingTxns.clear();
        for (Map.Entry<BlockType, ConcurrentLinkedQueue<Transaction>> entry : pendingTxnsByType.entrySet()) {
            List<Transaction> batch = new ArrayList<>();
            Transaction tx;
            while ((tx = entry.getValue().poll()) != null) {
                batch.add(tx);
            }
            if (!batch.isEmpty()) {
                result.put(entry.getKey(), batch);
            }
        }
        return result;
    }

    private void removeOutpoints(Transaction tx) {
        Set<TransactionOutPoint> outpoints = txOutpoints.remove(tx);
        if (outpoints != null) {
            for (TransactionOutPoint outpoint : outpoints) {
                spentOutpoints.remove(outpoint, tx);
            }
        }
    }

    public int size() {
        return pendingTxns.size();
    }

    /** Non-destructive snapshot of all pending transactions. */
    public List<Transaction> getPending() {
        return new ArrayList<>(pendingTxns);
    }

    public int getTotalSubmitted() {
        return totalSubmitted.get();
    }

    public void clear() {
        pendingTxns.clear();
        pendingTxnsByType.clear();
        spentOutpoints.clear();
        txOutpoints.clear();
    }

    public Set<TransactionOutPoint> getSpentOutpoints() {
        return Collections.unmodifiableSet(spentOutpoints.keySet());
    }

    public int getSpentOutpointsCount() {
        return spentOutpoints.size();
    }

    public static BlockType getTransactionType(Transaction tx) {
        String dataClassName = tx.getDataClassName();
        if (dataClassName == null) {
            return BlockType.BLOCKTYPE_TRANSFER;
        }
        switch (dataClassName) {
        case "OrderOpen":
            return BlockType.BLOCKTYPE_ORDER_OPEN;
        case "OrderCancelInfo":
            return BlockType.BLOCKTYPE_ORDER_CANCEL;
        case "ContractEventInfo":
            return BlockType.BLOCKTYPE_CONTRACT_EVENT;
        case "ContractEventCancelInfo":
            return BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL;
        case "EVMTransactionInfo": {
            try {
                net.bigtangle.core.EVMTransactionInfo info = new net.bigtangle.core.EVMTransactionInfo()
                        .parseChecked(tx.getData());
                return info.isDeploy() ? BlockType.BLOCKTYPE_EVM_DEPLOY : BlockType.BLOCKTYPE_EVM_CALL;
            } catch (RuntimeException e) {
                return BlockType.BLOCKTYPE_EVM_CALL;
            }
        }
        case "UserSettingDataInfo":
            return BlockType.BLOCKTYPE_USERDATA;
        case "LayerAnchor":
            // Cross-chain anchors are CROSSTANGLE blocks: without this mapping
            // the tx would be queued as TRANSFER, the block type would be lost
            // on the wire, and L0 would never record the anchor.
            return BlockType.BLOCKTYPE_CROSSTANGLE;
        default:
            return BlockType.BLOCKTYPE_TRANSFER;
        }
    }
}
