package net.bigtangle.server.service.base;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Block.Type;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventCancelInfo;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.Contractresult;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.script.Script;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.FullBlockStore;

public class ServiceContract extends ServiceBaseConnect {

	public ServiceContract(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService) {
		super(serverConfiguration, networkParameters, cacheBlockService);

	}

	private static final Logger log = LoggerFactory.getLogger(ServiceContract.class);

	/*
	 * the ContractEvent received and do next action
	 */
	public ContractExecutionResult executeContract(Block block, FullBlockStore blockStore, String contractid,
			Contractresult prevHash, Set<Sha256Hash> referencedblocks) throws BlockStoreException {

		Token contract = blockStore.getTokenID(contractid).get(0);
		String classname = getValue("classname", contract.getTokenKeyValues());
		if ("net.bigtangle.server.service.LotteryContract".equals(classname)) {
			return lotteryContract(block, blockStore, contract, prevHash, referencedblocks);
		}
		// TODO run others
		return null;

	}

	/*
	 * 
	 */
	public ContractExecutionResult lotteryContract(Block block, FullBlockStore store, Token contract,
			Contractresult prevHash, Set<Sha256Hash> collectedBlocks) throws BlockStoreException {

		// Deterministic randomization
		byte[] randomness = Utils.xor(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes());
		TreeMap<String, TreeMap<String, BigInteger>> payouts = new TreeMap<>();

		// Collect all orders approved by this block in the interval
		List<ContractEventCancelInfo> cancels = new ArrayList<>();

		TreeMap<Sha256Hash, ContractEventRecord> sortedNew = new TreeMap<>(Comparator
				.comparing(blockHash -> Sha256Hash.wrap(Utils.xor(((Sha256Hash) blockHash).getBytes(), randomness))));

		TreeMap<Sha256Hash, ContractEventRecord> toBeSpent = new TreeMap<>(Comparator
				.comparing(blockHash -> Sha256Hash.wrap(Utils.xor(((Sha256Hash) blockHash).getBytes(), randomness))));

		if (!Sha256Hash.ZERO_HASH.equals(prevHash.getBlockHash())) {
			// new must be from collectedBlocks
			toBeSpent.putAll(store.getContractEventPrev(contract.getTokenid(), prevHash.getBlockHash()));
		}

		// Set<ContractEventRecord> cancelled = new HashSet<>();

		collectWithCancel(block, collectedBlocks, cancels, sortedNew, toBeSpent, store);
		Set<ContractEventRecord> cancelledContractEventRecord = new HashSet<>();

		// timeoutOrdersToCancelled(block, toBeSpent, cancelledContractEventRecord);
		contractEventtoCancelled(cancels, toBeSpent, cancelledContractEventRecord);

		// Remove the now cancelled orders from rest of orders
		for (ContractEventRecord c : cancelledContractEventRecord) {
			toBeSpent.remove(c.getBlockHash());

		}

		// Add to proceeds all cancelled orders going back to the beneficiary
		payoutCancelled(payouts, cancelledContractEventRecord);

		String winnerAmount = getValue("winnerAmount", contract.getTokenKeyValues());
		String amount = getValue("amount", contract.getTokenKeyValues());

		TreeMap<Sha256Hash, ContractEventRecord> usedRecords = new TreeMap<>(Comparator
				.comparing(blockHash -> Sha256Hash.wrap(Utils.xor(((Sha256Hash) blockHash).getBytes(), randomness))));

		if (winnerAmount != null && canTakeWinner(toBeSpent, usedRecords, new BigInteger(winnerAmount))) {
			return doTakeWinner(block, store, usedRecords, new BigInteger(amount), prevHash, toBeSpent, collectedBlocks,
					payouts, getContractEventRecordHash(cancelledContractEventRecord));
		} else {
			if (!collectedBlocks.isEmpty()) {
				// no winner reset used
				usedRecords = new TreeMap<>(Comparator.comparing(
						blockHash -> Sha256Hash.wrap(Utils.xor(((Sha256Hash) blockHash).getBytes(), randomness))));
				Transaction tx = createPayoutTransaction(block, payouts);
				return new ContractExecutionResult(block.getHash(), contract.getTokenid(),
						getContractEventRecordHash(toBeSpent.values()), tx.getHash(), tx, prevHash.getBlockHash(),
						getContractEventRecordHash(cancelledContractEventRecord),
						getRemainder(toBeSpent.values(), usedRecords.values()), block.getTimeSeconds(),
						getRemainderContractEventRecord(toBeSpent.values(), usedRecords.values()),
						getContractEventRecordSet(toBeSpent.values()), collectedBlocks);

			}
		}
		return null;

	}

	protected void payoutCancelled(TreeMap<String, TreeMap<String, BigInteger>> payouts,
			Set<ContractEventRecord> cancelledOrders) {
		for (ContractEventRecord o : cancelledOrders) {
			payout(payouts, o.getBeneficiaryAddress(), o.getTargetTokenid(), o.getTargetValue());
		}
	}

	protected void payout(TreeMap<String, TreeMap<String, BigInteger>> payouts, String beneficiaryPubKey,
			String tokenid, BigInteger tokenValue) {
		TreeMap<String, BigInteger> proceeds = payouts.get(beneficiaryPubKey);
		if (proceeds == null) {
			proceeds = new TreeMap<>();
			payouts.put(beneficiaryPubKey, proceeds);
		}
		BigInteger offerTokenProceeds = proceeds.get(tokenid);
		if (offerTokenProceeds == null) {
			offerTokenProceeds = BigInteger.ZERO;
			proceeds.put(tokenid, offerTokenProceeds);
		}
		proceeds.put(tokenid, offerTokenProceeds.add(tokenValue));
	}

	protected void contractEventtoCancelled(List<ContractEventCancelInfo> cancels,
			TreeMap<Sha256Hash, ContractEventRecord> remaining, Set<ContractEventRecord> cancelled) {
		for (ContractEventCancelInfo c : cancels) {
			if (remaining.containsKey(c.getBlockHash())) {
				cancelled.add(remaining.get(c.getBlockHash()));
			}
		}
	}

	private void collectWithCancel(Block block, Set<Sha256Hash> collectedBlocks, List<ContractEventCancelInfo> cancels,
			Map<Sha256Hash, ContractEventRecord> news, TreeMap<Sha256Hash, ContractEventRecord> spents,
			FullBlockStore store) throws BlockStoreException {
		for (Sha256Hash bHash : collectedBlocks) {
			Block b = getBlock(bHash, store);
			if (b.getBlockType() == Type.BLOCKTYPE_CONTRACT_EVENT) {

				ContractEventRecord event = store.getContractEvent(b.getHash(), Sha256Hash.ZERO_HASH);
				// order is null, write it to
				if (event == null) {
					connectUTXOs(b, store);
					connectTypeSpecificUTXOs(b, store);
					event = store.getContractEvent(b.getHash(), Sha256Hash.ZERO_HASH);
				}
				if (event != null) {
					ContractEventRecord cloneOrderRecord = ContractEventRecord.cloneOrderRecord(event);
					news.put(b.getHash(), cloneOrderRecord);
					spents.put(b.getHash(), cloneOrderRecord);
				}
			} else if (b.getBlockType() == Type.BLOCKTYPE_CONTRACTEVENT_CANCEL) {
				ContractEventCancelInfo info = new ContractEventCancelInfo()
						.parseChecked(b.getTransactions().get(0).getData());
				cancels.add(info);
			}
		}
	}

	/*
	 * can be check on each node, the winner, the winnerBlock hash calculate the
	 * unique userAddress, userUtxos for check and generate the dynamic outputs
	 */
	private ContractExecutionResult doTakeWinner(Block winnerBlock, FullBlockStore blockStore,
			TreeMap<Sha256Hash, ContractEventRecord> usedRecords, BigInteger amount, Contractresult prevHash,
			TreeMap<Sha256Hash, ContractEventRecord> allRecords, Set<Sha256Hash> collectedBlocks,
			TreeMap<String, TreeMap<String, BigInteger>> payouts, Set<Sha256Hash> cancels) {
		// for the check the new ContractEventRecord is from the collectedBlocks
		// old ContractEventRecord are from the prevHash from the ContractEventRecord

		// Deterministic randomization
		long randomness = winnerBlock.getTimeSeconds();
		// .xor(winnerBlock.getPrevBranchBlockHash().toBigInteger());
		Random se = new Random(randomness);
		List<String> userlist = baseList(usedRecords.values(), amount);
		int randomWin = se.nextInt(userlist.size());
	//	log.debug("randomn win = " + randomWin + " userlist size =" + userlist.size());
		ContractEventRecord winner = findList(usedRecords.values(), userlist.get(randomWin));
	//	log.debug("winner = " + winner.toString());
		payout(payouts, winner.getBeneficiaryAddress(), winner.getTargetTokenid(), sum(usedRecords.values()));
		Transaction tx = createPayoutTransaction(winnerBlock, payouts);
		return new ContractExecutionResult(winnerBlock.getHash(), winner.getContractTokenid(),
				getContractEventRecordHash(allRecords.values()), tx.getHash(), tx, prevHash.getBlockHash(), cancels,
				getRemainder(allRecords.values(), usedRecords.values()), winnerBlock.getTimeSeconds(),
				getRemainderContractEventRecord(allRecords.values(), usedRecords.values()),
				getContractEventRecordSet(allRecords.values()), collectedBlocks);
	}

	public Set<Sha256Hash> getContractEventRecordHash(Collection<ContractEventRecord> orders) {
		Set<Sha256Hash> hashs = new HashSet<>();
		for (ContractEventRecord o : orders) {
			hashs.add(o.getBlockHash());
		}
		return hashs;
	}

	public Set<ContractEventRecord> getContractEventRecordSet(Collection<ContractEventRecord> orders) {
		Set<ContractEventRecord> hashs = new HashSet<>();
		for (ContractEventRecord o : orders) {
			hashs.add(o);
		}
		return hashs;
	}

	public Set<Sha256Hash> getContractEventCancel(Collection<ContractEventRecord> orders) {
		Set<Sha256Hash> hashs = new HashSet<>();
		for (ContractEventRecord o : orders) {
			hashs.add(o.getBlockHash());
		}
		return hashs;
	}

	public Set<Sha256Hash> getRemainder(Collection<ContractEventRecord> all, Collection<ContractEventRecord> used) {
		Set<Sha256Hash> hashs = new HashSet<>();
		for (ContractEventRecord o : all) {
			if (!used.contains(o)) {
				hashs.add(o.getBlockHash());
			}
		}
		return hashs;
	}

	public Set<ContractEventRecord> getRemainderContractEventRecord(Collection<ContractEventRecord> all,
			Collection<ContractEventRecord> used) {
		Set<ContractEventRecord> re = new HashSet<>();
		for (ContractEventRecord o : all) {
			if (!used.contains(o)) {
				o.setConfirmed(true);
				re.add(o);
			}
		}
		return re;
	}

	/*
	 * condition for execute the lottery 1) no other pending payment 2) can do the
	 * send failed block again 3) the sum is ok
	 */
	private boolean canTakeWinner(TreeMap<Sha256Hash, ContractEventRecord> player,
			TreeMap<Sha256Hash, ContractEventRecord> userlist, BigInteger winnerAmount) {

		BigInteger sum = BigInteger.ZERO;

		for (Map.Entry<Sha256Hash, ContractEventRecord> u : player.entrySet()) {
			sum = sum.add(u.getValue().getTargetValue());
			userlist.put(u.getKey(), u.getValue());
			if (sum.compareTo(winnerAmount) >= 0) {
				return true;
			}
		}
		// log.debug(" sum= " + sum);
		return false;

	}

	public Transaction createPayoutTransaction(Block block, TreeMap<String, TreeMap<String, BigInteger>> payouts) {
		Transaction tx = new Transaction(networkParameters);
		for (Entry<String, TreeMap<String, BigInteger>> payout : payouts.entrySet()) {
			for (Entry<String, BigInteger> tokenProceeds : payout.getValue().entrySet()) {
				String tokenId = tokenProceeds.getKey();
				BigInteger proceedsValue = tokenProceeds.getValue();

				if (proceedsValue.signum() != 0)
					tx.addOutput(new Coin(proceedsValue, tokenId), new Address(networkParameters, payout.getKey()));
			}
		}

		// The coinbase input does not really need to be a valid signature
		TransactionInput input = new TransactionInput(networkParameters, tx, Script
				.createInputScript(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes()));
		tx.addInput(input);
		tx.setMemo(new MemoInfo("contractExecution"));

		return tx;
	}

	private List<String> baseList(Collection<ContractEventRecord> userlist, BigInteger baseAmount) {
		List<String> addresses = new ArrayList<String>();

		for (ContractEventRecord eventRecord : userlist) {
			int multi = eventRecord.getTargetValue().divide(baseAmount).intValue();
			for (int i = 0; i < multi; i++) {
				addresses.add(eventRecord.getBeneficiaryAddress());
			}
		}

		return addresses;
	}

	private ContractEventRecord findList(Collection<ContractEventRecord> userlist, String address) {
		for (ContractEventRecord u : userlist) {
			if (address.equals(u.getBeneficiaryAddress())) {
				return u;
			}
		}
		return null;
	}

	public BigInteger sum(Collection<ContractEventRecord> opens) {
		BigInteger sum = BigInteger.ZERO;
		for (ContractEventRecord u : opens) {
			sum = sum.add(u.getTargetValue());
		}
		return sum;
	}

	public String getValue(String key, TokenKeyValues kvs) {
		for (KeyValue k : kvs.getKeyvalues()) {
			if (key.equals(k.getKey())) {
				return k.getValue();
			}
		}
		return null;

	}

}
