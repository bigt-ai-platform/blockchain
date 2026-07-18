/*******************************************************************************
 *  Copyright   2018  Inasset GmbH.
 *******************************************************************************/
package net.bigtangle.mcmc.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;

import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException.InfeasiblePrototypeException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class TipsService {

    private final Logger log = LoggerFactory.getLogger(TipsService.class);

    @Autowired
    private ServerConfiguration serverConfiguration;
    @Autowired
    protected NetworkParameters networkParameters;
    @Autowired
    protected CacheBlockService cacheBlockService;
    @Autowired
    protected ObjectMapper jsonmapper;

    private static final Random seed = new Random();

    private static final int EXP_TABLE_MIN = -200;
    private static final int EXP_TABLE_MAX = 200;
    private static final int EXP_TABLE_SIZE = EXP_TABLE_MAX - EXP_TABLE_MIN + 1;
    private static volatile double[] expTable;
    private static volatile double expTableAlpha;

    private static double fastExp(double alpha, long diff) {
        if (diff >= EXP_TABLE_MIN && diff <= EXP_TABLE_MAX) {
            double[] table = expTable;
            if (table == null || expTableAlpha != alpha) {
                table = new double[EXP_TABLE_SIZE];
                for (int i = EXP_TABLE_MIN; i <= EXP_TABLE_MAX; i++) {
                    table[i - EXP_TABLE_MIN] = Math.exp(alpha * i);
                }
                expTable = table;
                expTableAlpha = alpha;
            }
            return table[(int) diff - EXP_TABLE_MIN];
        }
        return Math.exp(alpha * diff);
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors());

    private class RatingTipWalker implements Callable<BlockWrap> {
        final BlockWrap entryPoint;
        long maxHeight;
        final BlockStoreInterface store;
        final HashMap<Sha256Hash, List<BlockWrap>> approverCache;

        RatingTipWalker(BlockWrap entryPoint, long maxHeight, BlockStoreInterface store,
                HashMap<Sha256Hash, List<BlockWrap>> approverCache) {
            this.entryPoint = entryPoint;
            this.maxHeight = maxHeight;
            this.store = store;
            this.approverCache = approverCache;
        }

        @Override
        public BlockWrap call() throws Exception {
            return getRatingTip(entryPoint, Long.MAX_VALUE, maxHeight, store, approverCache);
        }
    }

    public Collection<BlockWrap> getRatingTips(TXReward maxConfirmedReward, int count, long maxHeight,
            BlockStoreInterface store) throws BlockStoreException {
        Stopwatch watch = Stopwatch.createStarted();
        List<BlockWrap> entryPoints = getEntryPoints(count, maxConfirmedReward.getChainLength(), store);
        List<Future<BlockWrap>> ratingTipFutures = new ArrayList<>(count);
        List<BlockWrap> ratingTips = new ArrayList<>(count);
        for (BlockWrap entryPoint : entryPoints) {
            HashMap<Sha256Hash, List<BlockWrap>> approverCache = new HashMap<>();
            FutureTask<BlockWrap> future = new FutureTask<>(
                    new RatingTipWalker(entryPoint, maxHeight, store, approverCache));
            executor.execute(future);
            ratingTipFutures.add(future);
        }
        for (Future<BlockWrap> future : ratingTipFutures) {
            try {
                ratingTips.add(future.get(10, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                log.error("Task timed out for entry point: ", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Processing interrupted", e);
            } catch (ExecutionException e) {
                throw new BlockStoreException(e);
            }
        }
        watch.stop();
        log.trace("getRatingTips with count {} time {} ms.", count, watch.elapsed(TimeUnit.MILLISECONDS));
        return ratingTips;
    }

    public Pair<BlockWrap, BlockWrap> getValidatedBlockPair(BlockStoreInterface store) throws BlockStoreException {
        return getValidatedBlockPair(cacheBlockService.getMaxConfirmedReward(store), new HashSet<>(), store);
    }

    public Pair<BlockWrap, BlockWrap> getValidatedBlockPair(TXReward maxConfirmedReward,
            HashSet<BlockWrap> currentApprovedNonMilestoneBlocks, BlockStoreInterface store)
            throws BlockStoreException {
        List<BlockWrap> entryPoints = getEntryPoints(2, maxConfirmedReward.getChainLength(), store);
        BlockWrap left = entryPoints.get(0);
        BlockWrap right = entryPoints.get(1);
        Pair<BlockWrap, BlockWrap> candidate = getValidatedBlockPair(maxConfirmedReward,
                currentApprovedNonMilestoneBlocks, left, right, store);
        if (!candidate.getLeft().equals(candidate.getRight())) {
            return candidate;
        }
        for (int i = 0; i < 5; i++) {
            Pair<BlockWrap, BlockWrap> paar = getValidatedBlockPair(maxConfirmedReward,
                    currentApprovedNonMilestoneBlocks, left, right, store);
            if (!paar.getLeft().equals(paar.getRight())) {
                return paar;
            }
        }
        return candidate;
    }

    private Pair<BlockWrap, BlockWrap> getValidatedBlockPair(TXReward maxConfirmedReward,
            HashSet<BlockWrap> currentApprovedUnconfirmedBlocks, BlockWrap left, BlockWrap right,
            BlockStoreInterface store) throws BlockStoreException {
        Stopwatch watch = Stopwatch.createStarted();
        ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, jsonmapper);
        long cutoffHeight = serviceBase.getCurrentCutoffHeight(maxConfirmedReward, store);
        long maxHeight = serviceBase.getCurrentMaxHeight(maxConfirmedReward, store);
        serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight, store);
        serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight, store);
        if (!serviceBase.isEligibleForApprovalSelection(currentApprovedUnconfirmedBlocks, store))
            throw new InfeasiblePrototypeException("The given prototype is invalid under the current milestone");
        return getValidatedBlockPair(currentApprovedUnconfirmedBlocks, left, right, store, watch, serviceBase,
                cutoffHeight, maxHeight);
    }

    private Pair<BlockWrap, BlockWrap> getValidatedBlockPair(HashSet<BlockWrap> currentApprovedUnconfirmedBlocks,
            BlockWrap left, BlockWrap right, BlockStoreInterface store, Stopwatch watch,
            ServiceBaseConnect serviceBase, long cutoffHeight, long maxHeight) throws BlockStoreException {
        BlockWrap leftCapture = left;
        BlockWrap rightCapture = right;
        HashSet<BlockWrap> rightCopy = new HashSet<>(currentApprovedUnconfirmedBlocks);
        BlockWrap nextLeft;
        BlockWrap nextRight;
        try {
            Future<BlockWrap> leftFuture = executor.submit(() ->
                performValidatedStep(leftCapture, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store));
            Future<BlockWrap> rightFuture = executor.submit(() ->
                performValidatedStep(rightCapture, rightCopy, cutoffHeight, maxHeight, store));
            nextLeft = leftFuture.get(30, TimeUnit.SECONDS);
            nextRight = rightFuture.get(30, TimeUnit.SECONDS);
            currentApprovedUnconfirmedBlocks.addAll(rightCopy);
        } catch (Exception e) {
            throw new BlockStoreException(e);
        }
        while (nextLeft != left && nextRight != right) {
            try {
                BlockMCMC nextLeftMcmc = cacheBlockService.getBlockMCMCAsObject(nextLeft.getBlockHash(), store);
                BlockMCMC nextRightMcmc = cacheBlockService.getBlockMCMCAsObject(nextRight.getBlockHash(), store);
                if (nextLeftMcmc.getRating() > nextRightMcmc.getRating()) {
                    left = nextLeft;
                    serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight, store);
                    nextLeft = performValidatedStep(left, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store);
                    nextRight = validateOrPerformValidatedStep(right, currentApprovedUnconfirmedBlocks, nextRight,
                            cutoffHeight, maxHeight, store);
                } else {
                    right = nextRight;
                    serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight, store);
                    nextRight = performValidatedStep(right, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store);
                    nextLeft = validateOrPerformValidatedStep(left, currentApprovedUnconfirmedBlocks, nextLeft,
                            cutoffHeight, maxHeight, store);
                }
            } catch (Exception e) {
                throw new BlockStoreException(e);
            }
        }
        while (nextLeft != left) {
            left = nextLeft;
            serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight, store);
            nextLeft = performValidatedStep(left, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store);
        }
        while (nextRight != right) {
            right = nextRight;
            serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight, store);
            nextRight = performValidatedStep(right, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store);
        }
        watch.stop();
        log.trace("getValidatedBlockPair iteration time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
        return Pair.of(left, right);
    }

    private BlockWrap validateOrPerformValidatedStep(BlockWrap fromBlock,
            HashSet<BlockWrap> currentApprovedNonMilestoneBlocks, BlockWrap potentialNextBlock,
            long cutoffHeight, long maxHeight, BlockStoreInterface store) throws BlockStoreException {
        if (new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
                .isEligibleForApprovalSelection(potentialNextBlock, currentApprovedNonMilestoneBlocks,
                        cutoffHeight, maxHeight, store))
            return potentialNextBlock;
        else
            return performValidatedStep(fromBlock, currentApprovedNonMilestoneBlocks, cutoffHeight, maxHeight, store);
    }

    private BlockWrap performValidatedStep(BlockWrap fromBlock,
            HashSet<BlockWrap> currentApprovedNonMilestoneBlocks, long cutoffHeight, long maxHeight,
            BlockStoreInterface store) throws BlockStoreException {
        List<BlockWrap> candidates = new ArrayList<>();
        for (Sha256Hash req : store.getApproverBlockHashes(fromBlock.getBlockHash())) {
            candidates.add(new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
                    .getBlockWrap(req, store));
        }
        BlockWrap result;
        do {
            result = performTransition(fromBlock, candidates, store);
            candidates.remove(result);
        } while (!new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
                .isEligibleForApprovalSelection(result, currentApprovedNonMilestoneBlocks,
                        cutoffHeight, maxHeight, store));
        return result;
    }

    private BlockWrap getRatingTip(BlockWrap currentBlock, long maxTime, long maxHeight,
            BlockStoreInterface store, HashMap<Sha256Hash, List<BlockWrap>> approverCache)
            throws BlockStoreException {
        List<BlockWrap> approvers = getCachedApprovers(currentBlock.getBlock().getHash(), store, approverCache);
        approvers.removeIf(b -> b.getBlockEvaluation().getInsertTime() > maxTime);
        BlockWrap nextBlock = performTransition(currentBlock, approvers, store);
        while (currentBlock != nextBlock && nextBlock.getBlockEvaluation().getHeight() <= maxHeight) {
            currentBlock = nextBlock;
            approvers = getCachedApprovers(currentBlock.getBlock().getHash(), store, approverCache);
            approvers.removeIf(b -> b.getBlockEvaluation().getInsertTime() > maxTime);
            nextBlock = performTransition(currentBlock, approvers, store);
        }
        return currentBlock;
    }

    private List<BlockWrap> getCachedApprovers(Sha256Hash hash, BlockStoreInterface store,
            HashMap<Sha256Hash, List<BlockWrap>> cache) {
        return cache.computeIfAbsent(hash, h -> {
            try {
                return store.getNotInvalidApproverBlocks(h);
            } catch (BlockStoreException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public BlockWrap performTransition(BlockWrap currentBlock, List<BlockWrap> candidates,
            BlockStoreInterface store) throws BlockStoreException {
        if (candidates.isEmpty()) {
            return currentBlock;
        } else if (candidates.size() == 1) {
            return candidates.get(0);
        } else {
            try {
                double[] transitionWeights = new double[candidates.size()];
                double transitionWeightSum = 0;
                BlockMCMC currentMcmc = cacheBlockService.getBlockMCMCAsObject(currentBlock.getBlockHash(), store);
                long currentCumulativeWeight = currentMcmc.getCumulativeWeight();
                double alpha = serverConfiguration.getAlphaMCMC();
                for (int i = 0; i < candidates.size(); i++) {
                    BlockMCMC candidateMcmc = cacheBlockService.getBlockMCMCAsObject(
                            candidates.get(i).getBlockHash(), store);
                    transitionWeights[i] = fastExp(alpha,
                            currentCumulativeWeight - candidateMcmc.getCumulativeWeight());
                    transitionWeightSum += transitionWeights[i];
                }
                double transitionRealization = seed.nextDouble() * transitionWeightSum;
                for (int i = 0; i < candidates.size(); i++) {
                    transitionRealization -= transitionWeights[i];
                    if (transitionRealization <= 0) {
                        return candidates.get(i);
                    }
                }
                log.warn("MCMC step failed");
                return currentBlock;
            } catch (Exception e) {
                throw new BlockStoreException(e);
            }
        }
    }

    private List<BlockWrap> getEntryPoints(int count, long currChainLength, BlockStoreInterface store)
            throws BlockStoreException {
        List<BlockWrap> candidates = new ArrayList<>();
        List<Sha256Hash> hashs = getEntryPointCandidates(currChainLength, store);
        if (hashs.isEmpty()) {
            candidates.add(store.getBlockWrap(cacheBlockService.getMaxConfirmedReward(store).getBlockHash()));
        } else {
            ServiceBaseConnect serviceBaseConnect = new ServiceBaseConnect(serverConfiguration, networkParameters,
                    cacheBlockService, jsonmapper);
            for (Sha256Hash hash : hashs) {
                candidates.add(serviceBaseConnect.getBlockWrap(hash, store));
            }
        }
        return pullRandomlyByCumulativeWeight(candidates, count, store);
    }

    private List<Sha256Hash> getEntryPointCandidates(long currChainLength, BlockStoreInterface store)
            throws BlockStoreException {
        return new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
                .getEntryPointCandidates(currChainLength, store);
    }

    private List<BlockWrap> pullRandomlyByCumulativeWeight(List<BlockWrap> candidates, int count,
            BlockStoreInterface store) throws BlockStoreException {
        if (candidates.isEmpty())
            throw new IllegalArgumentException("Candidate list is empty.");
        try {
            double maxBlockWeight = candidates.stream().mapToLong(e -> {
                try {
                    BlockMCMC mcmc = cacheBlockService.getBlockMCMCAsObject(e.getBlockHash(), store);
                    return mcmc.getCumulativeWeight();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }).max().orElse(1L);
            double normalizedBlockWeightSum = candidates.stream()
                    .mapToDouble(e -> {
                        try {
                            BlockMCMC mcmc = cacheBlockService.getBlockMCMCAsObject(e.getBlockHash(), store);
                            return mcmc.getCumulativeWeight() / maxBlockWeight;
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }).sum();
            List<BlockWrap> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                double selectionRealization = seed.nextDouble() * normalizedBlockWeightSum;
                for (BlockWrap selectedBlock : candidates) {
                    BlockMCMC mcmc = cacheBlockService.getBlockMCMCAsObject(selectedBlock.getBlockHash(), store);
                    selectionRealization -= mcmc.getCumulativeWeight() / maxBlockWeight;
                    if (selectionRealization <= 0) {
                        results.add(selectedBlock);
                        break;
                    }
                }
            }
            return results;
        } catch (Exception e) {
            throw new BlockStoreException(e);
        }
    }
}
