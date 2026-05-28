package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.exception.ProtocolException;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class BlockServiceCreate {

    private static final Logger logger = LoggerFactory.getLogger(BlockServiceCreate.class);

    @Autowired
    protected CacheBlockPrototypeService cacheBlockPrototypeService;

    public void adjustHeightRequiredBlocks(Block block, BlockStoreInterface store)
            throws BlockStoreException, NoBlockException {
        block = adjustPrototype(block, store);
        long h = calcHeightRequiredBlocks(block, store);
        if (h > block.getHeight()) {
            logger.debug("adjustHeightRequiredBlocks{} to {}", block, h);
            block.setHeight(h);
        }
    }

    public Block adjustPrototype(Block block, BlockStoreInterface store)
            throws BlockStoreException, ProtocolException, NoBlockException {
        int delaySeconds = 7200;
        if (block.getTimeSeconds() < System.currentTimeMillis() / 1000 - delaySeconds) {
            logger.debug("adjustPrototype {}", block);
            Block newblock = cacheBlockPrototypeService.getBlockPrototype(store);
            for (Transaction transaction : block.getTransactions()) {
                newblock.addTransaction(transaction);
            }
            return newblock;
        }
        return block;
    }

    public long calcHeightRequiredBlocks(Block block, BlockStoreInterface store) throws BlockStoreException {
        Set<Sha256Hash> allrequireds = new HashSet<>();
        List<Block> result = new ArrayList<>();
        allrequireds.add(block.getPrevBlockHash());
        allrequireds.add(block.getPrevBranchBlockHash());
        for (Sha256Hash pred : allrequireds)
            result.add(store.get(pred));
        long height = 0;
        for (Block b : result) {
            height = Math.max(height, b.getHeight());
        }
        return height + 1;
    }
}
