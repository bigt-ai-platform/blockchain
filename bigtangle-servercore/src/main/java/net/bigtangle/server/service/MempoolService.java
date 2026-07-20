package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Transaction;

@Service
public class MempoolService {

    private static final Logger log = LoggerFactory.getLogger(MempoolService.class);

    private final ConcurrentLinkedQueue<Transaction> pendingTxns = new ConcurrentLinkedQueue<>();

    private final Map<BlockType, ConcurrentLinkedQueue<Transaction>> pendingTxnsByType = new EnumMap<>(
            BlockType.class);

    private final AtomicInteger totalSubmitted = new AtomicInteger(0);

    public void submit(Block block) {
        BlockType blockType = block.getBlockType();
        List<Transaction> txs = block.getTransactions();
        if (txs != null) {
            ConcurrentLinkedQueue<Transaction> typeQueue = pendingTxnsByType
                    .computeIfAbsent(blockType, k -> new ConcurrentLinkedQueue<>());
            for (Transaction tx : txs) {
                pendingTxns.add(tx);
                typeQueue.add(tx);
            }
            totalSubmitted.addAndGet(txs.size());
        }
    }

    public void submitTransaction(Transaction tx) {
        BlockType blockType = getTransactionType(tx);
        ConcurrentLinkedQueue<Transaction> typeQueue = pendingTxnsByType
                .computeIfAbsent(blockType, k -> new ConcurrentLinkedQueue<>());
        pendingTxns.add(tx);
        typeQueue.add(tx);
        totalSubmitted.incrementAndGet();
    }

    public List<Transaction> drainAll() {
        List<Transaction> batch = new ArrayList<>();
        Transaction tx;
        while ((tx = pendingTxns.poll()) != null) {
            batch.add(tx);
        }
        for (ConcurrentLinkedQueue<Transaction> queue : pendingTxnsByType.values()) {
            queue.clear();
        }
        return batch;
    }

    public Map<BlockType, List<Transaction>> drainAllByType() {
        Map<BlockType, List<Transaction>> result = new EnumMap<>(BlockType.class);
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

    public int size() {
        return pendingTxns.size();
    }

    public int getTotalSubmitted() {
        return totalSubmitted.get();
    }

    public void clear() {
        pendingTxns.clear();
        pendingTxnsByType.clear();
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
        case "UserSettingDataInfo":
            return BlockType.BLOCKTYPE_USERDATA;
        default:
            return BlockType.BLOCKTYPE_TRANSFER;
        }
    }
}
