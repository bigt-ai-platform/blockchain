package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.Transaction;

@Service
public class MempoolService {

    private static final Logger log = LoggerFactory.getLogger(MempoolService.class);

    private final ConcurrentLinkedQueue<Transaction> pendingTxns = new ConcurrentLinkedQueue<>();

    private final AtomicInteger totalSubmitted = new AtomicInteger(0);

    public void submit(Block block) {
        List<Transaction> txs = block.getTransactions();
        if (txs != null) {
            for (Transaction tx : txs) {
                pendingTxns.add(tx);
            }
            totalSubmitted.addAndGet(txs.size());
        }
    }

    public void submitTransaction(Transaction tx) {
        pendingTxns.add(tx);
        totalSubmitted.incrementAndGet();
    }

    public List<Transaction> drainAll() {
        List<Transaction> batch = new ArrayList<>();
        Transaction tx;
        while ((tx = pendingTxns.poll()) != null) {
            batch.add(tx);
        }
        return batch;
    }

    public int size() {
        return pendingTxns.size();
    }

    public int getTotalSubmitted() {
        return totalSubmitted.get();
    }

    public void clear() {
        pendingTxns.clear();
    }
}
