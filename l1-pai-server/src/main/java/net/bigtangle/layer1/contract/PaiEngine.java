package net.bigtangle.layer1.contract;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventCancelInfo;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.service.base.handler.ContractConnectSupport;
import net.bigtangle.server.service.base.handler.ContractExecutor;
import net.bigtangle.store.BlockStoreInterface;

public class PaiEngine implements ContractExecutor {

    private static final String KEY_CLASSNAME = "classname";

    private static final String CLASSNAME_STAKING = "net.bigtangle.server.service.AiStakingContract";
    private static final String CLASSNAME_REPUTATION = "net.bigtangle.server.service.AiReputationContract";
    private static final String CLASSNAME_REWARD = "net.bigtangle.server.service.AiRewardContract";

    @Override
    public ContractExecutionResult executeContract(ContractConnectSupport support,
            NetworkParameters networkParameters, Block block, BlockStoreInterface blockStore,
            String contractid, Contractresult prevHash, Set<Sha256Hash> referencedblocks)
            throws BlockStoreException {
        Token contract = blockStore.getTokenID(contractid).get(0);
        return executeContract(support, networkParameters, block, blockStore, contract, prevHash, referencedblocks);
    }

    public ContractExecutionResult executeContract(ContractConnectSupport support,
            NetworkParameters networkParameters, Block block, BlockStoreInterface blockStore,
            Token contract, Contractresult prevHash, Set<Sha256Hash> referencedblocks)
            throws BlockStoreException {

        String classname = getValue(KEY_CLASSNAME, contract.getTokenKeyValues());
        if (CLASSNAME_STAKING.equals(classname)) {
            return stakingContract(support, block, blockStore, contract, prevHash, referencedblocks);
        } else if (CLASSNAME_REPUTATION.equals(classname)) {
            return reputationContract(support, block, blockStore, contract, prevHash, referencedblocks);
        } else if (CLASSNAME_REWARD.equals(classname)) {
            return rewardContract(support, networkParameters, block, blockStore, contract, prevHash, referencedblocks);
        }
        return null;
    }

    private ContractExecutionResult stakingContract(ContractConnectSupport support,
            Block block, BlockStoreInterface store,
            Token contract, Contractresult prevContractresult, Set<Sha256Hash> collectedBlocks)
            throws BlockStoreException {

        byte[] randomness = Utils.xor(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes());
        TreeMap<Sha256Hash, ContractEventRecord> toBeSpent = new TreeMap<>(
                Comparator.comparing(h -> Sha256Hash.wrap(Utils.xor(h.getBytes(), randomness))));

        if (!Sha256Hash.ZERO_HASH.equals(prevContractresult.getBlockHash())) {
            toBeSpent.putAll(store.getContractEventPrev(contract.getTokenid(), prevContractresult.getBlockHash()));
        }

        List<ContractEventCancelInfo> cancels = new ArrayList<>();
        collectEvents(support, collectedBlocks, cancels, toBeSpent, store);

        Set<ContractEventRecord> cancelled = cancelEvents(cancels, toBeSpent);

        return new ContractExecutionResult(contract.getTokenid(), Sha256Hash.ZERO_HASH, null,
                prevContractresult.getBlockHash(),
                getHashSet(cancelled), getHashSet(toBeSpent.values()),
                block.getTimeSeconds(), new HashSet<>(toBeSpent.values()), collectedBlocks,
                prevContractresult.getChainlength() + 1);
    }

    private ContractExecutionResult reputationContract(ContractConnectSupport support,
            Block block, BlockStoreInterface store,
            Token contract, Contractresult prevContractresult, Set<Sha256Hash> collectedBlocks)
            throws BlockStoreException {

        byte[] randomness = Utils.xor(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes());
        TreeMap<Sha256Hash, ContractEventRecord> toBeSpent = new TreeMap<>(
                Comparator.comparing(h -> Sha256Hash.wrap(Utils.xor(h.getBytes(), randomness))));

        if (!Sha256Hash.ZERO_HASH.equals(prevContractresult.getBlockHash())) {
            toBeSpent.putAll(store.getContractEventPrev(contract.getTokenid(), prevContractresult.getBlockHash()));
        }

        List<ContractEventCancelInfo> cancels = new ArrayList<>();
        collectEvents(support, collectedBlocks, cancels, toBeSpent, store);

        Set<ContractEventRecord> cancelled = cancelEvents(cancels, toBeSpent);

        return new ContractExecutionResult(contract.getTokenid(), Sha256Hash.ZERO_HASH, null,
                prevContractresult.getBlockHash(),
                getHashSet(cancelled), getHashSet(toBeSpent.values()),
                block.getTimeSeconds(), new HashSet<>(toBeSpent.values()), collectedBlocks,
                prevContractresult.getChainlength() + 1);
    }

    private ContractExecutionResult rewardContract(ContractConnectSupport support,
            NetworkParameters networkParameters, Block block, BlockStoreInterface store,
            Token contract, Contractresult prevContractresult, Set<Sha256Hash> collectedBlocks)
            throws BlockStoreException {

        byte[] randomness = Utils.xor(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes());
        TreeMap<Sha256Hash, ContractEventRecord> toBeSpent = new TreeMap<>(
                Comparator.comparing(h -> Sha256Hash.wrap(Utils.xor(h.getBytes(), randomness))));

        if (!Sha256Hash.ZERO_HASH.equals(prevContractresult.getBlockHash())) {
            toBeSpent.putAll(store.getContractEventPrev(contract.getTokenid(), prevContractresult.getBlockHash()));
        }

        List<ContractEventCancelInfo> cancels = new ArrayList<>();
        collectEvents(support, collectedBlocks, cancels, toBeSpent, store);

        Set<ContractEventRecord> cancelled = cancelEvents(cancels, toBeSpent);

        return new ContractExecutionResult(contract.getTokenid(), Sha256Hash.ZERO_HASH, null,
                prevContractresult.getBlockHash(),
                getHashSet(cancelled), getHashSet(toBeSpent.values()),
                block.getTimeSeconds(), new HashSet<>(toBeSpent.values()), collectedBlocks,
                prevContractresult.getChainlength() + 1);
    }

    private Set<ContractEventRecord> cancelEvents(List<ContractEventCancelInfo> cancels,
            TreeMap<Sha256Hash, ContractEventRecord> toBeSpent) {
        Set<ContractEventRecord> cancelled = new HashSet<>();
        for (ContractEventCancelInfo c : cancels) {
            ContractEventRecord removed = toBeSpent.remove(c.getBlockHash());
            if (removed != null) {
                cancelled.add(removed);
            }
        }
        return cancelled;
    }

    private void collectEvents(ContractConnectSupport support, Set<Sha256Hash> collectedBlocks,
            List<ContractEventCancelInfo> cancels,
            TreeMap<Sha256Hash, ContractEventRecord> spents,
            BlockStoreInterface store) throws BlockStoreException {
        for (Sha256Hash bHash : collectedBlocks) {
            Block b = support.getBlock(bHash, store);
            if (b.getBlockType() == BlockType.BLOCKTYPE_CONTRACT_EVENT) {
                ContractEventRecord event = store.getContractEvent(b.getHash(), Sha256Hash.ZERO_HASH);
                if (event == null) {
                    support.connectUTXOs(b, store);
                    support.connectTypeSpecificUTXOs(b, store);
                    event = store.getContractEvent(b.getHash(), Sha256Hash.ZERO_HASH);
                }
                if (event != null) {
                    spents.put(b.getHash(), ContractEventRecord.cloneOrderRecord(event));
                }
            } else if (b.getBlockType() == BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL) {
                ContractEventCancelInfo info = new ContractEventCancelInfo()
                        .parseChecked(b.getTransactions().get(0).getData());
                cancels.add(info);
            }
        }
    }

    private Set<Sha256Hash> getHashSet(java.util.Collection<ContractEventRecord> records) {
        Set<Sha256Hash> hashs = new HashSet<>();
        for (ContractEventRecord r : records) {
            hashs.add(r.getBlockHash());
        }
        return hashs;
    }

    private String getValue(String key, TokenKeyValues kvs) {
        if (kvs == null) return null;
        for (KeyValue k : kvs.getKeyvalues()) {
            if (key.equals(k.getKey())) {
                return k.getValue();
            }
        }
        return null;
    }
}
