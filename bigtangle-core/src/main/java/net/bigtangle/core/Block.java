/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bigtangle.core;

import static net.bigtangle.core.Sha256Hash.hashTwice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import jakarta.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import net.bigtangle.crypto.pq.KeyBundle;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.crypto.pq.SignatureBundle;

import net.bigtangle.exception.ProtocolException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.exception.VerificationException.CoinbaseDisallowedException;
import net.bigtangle.exception.VerificationException.LargerThanMaxBlockSize;
import net.bigtangle.exception.VerificationException.MerkleRootMismatchException;
import net.bigtangle.exception.VerificationException.SigOpsException;
import net.bigtangle.exception.VerificationException.TimeTravelerException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;

/**
 * <p>
 * A block is a group of transactions, and is one of the fundamental data
 * structures of the Bitcoin system. It records a set of {@link Transaction}s
 * together with some data that links it into a place in the global block
 * structure, and proves that a difficult calculation was done over its
 * contents. See <a href="http://bitcoin.net/bigtangle.pdf">the white paper</a>
 * for more detail on blocks.
 * <p/>
 *
 * <p>
 * To get a block, you can either build one from the raw bytes you can get from
 * another implementation, or request one specifically using
 * </p>
 * 
 */
public class Block extends Message {
	private static final Logger log = LoggerFactory.getLogger(Block.class);

	// Fields defined as part of the protocol format.
	private long version;
	private Sha256Hash prevBlockHash;
	private Sha256Hash prevBranchBlockHash; // second predecessor
	private Sha256Hash merkleRoot;
	private long time;
	private long lastMiningRewardBlock; // last approved reward blocks max
	private BlockType blockType;
	private long height;

	/** Proposer post-quantum KeyBundle (nullable, not yet serialized to wire). */
	@Nullable
	private byte[] proposerKeyBundle;

	/** Proposer post-quantum SignatureBundle (nullable, not yet serialized to wire). */
	@Nullable
	private byte[] proposerSignatureBundle;

	/** If null, it means this object holds only the headers. */
	@Nullable
	List<Transaction> transactions;

	/** Stores the hash of the block. If null, getHash() will recalculate it. */
	private Sha256Hash hash;

	protected boolean headerBytesValid;
	protected boolean transactionBytesValid;

	// Blocks can be encoded in a way that will use more bytes than is optimal
	// (due to VarInts having multiple encodings)
	// MAX_BLOCK_SIZE must be compared to the optimal encoding, not the actual
	// encoding, so when parsing, we keep track
	// of the size of the ideal encoding in addition to the actual message size
	// (which Message needs)
	protected int optimalEncodingMessageSize;

	public Block(NetworkParameters params) {
		super(params);
	}

	public static Block setBlock2(NetworkParameters params, long setVersion) {
		return Block.setBlock7(params, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH,
				BlockType.BLOCKTYPE_TRANSFER.name(), 0, 0);
	}

	public static Block createBlock(NetworkParameters networkParameters, Block r1, Block r2) {
		Block block = Block.setBlock7(networkParameters, r1.getHash(), r2.getHash(),
				BlockType.BLOCKTYPE_TRANSFER.name(), Math.max(r1.getTimeSeconds(), r2.getTimeSeconds()),
				Math.max(r1.getLastMiningRewardBlock(), r2.getLastMiningRewardBlock()));
		block.setHeight(Math.max(r1.getHeight(), r2.getHeight()) + 1);

		return block;
	}

	public static Block setBlock7(NetworkParameters params, Sha256Hash prevBlockHash, Sha256Hash prevBranchBlockHash,
			String blocktype, long minTime, long lastMiningRewardBlock) {
		Block a = new Block(params);
		// Set up a few basic things. We are not complete after this though.
		a.version = NetworkParameters.BLOCK_VERSION_GENESIS;
		a.lastMiningRewardBlock = lastMiningRewardBlock;
		a.time = System.currentTimeMillis() / 1000;
		if (a.time < minTime)
			a.time = minTime;
		a.prevBlockHash = prevBlockHash;
		a.prevBranchBlockHash = prevBranchBlockHash;

		a.blockType = BlockType.valueOf(blocktype);
		a.length = NetworkParameters.HEADER_SIZE;
		a.transactions = new ArrayList<>();
		return a;
	}

	/**
	 * Construct a block object from the Bitcoin wire format.
	 * 
	 * @param params       NetworkParameters object.
	 * @param payloadBytes the payload to extract the block from.
	 * @param serializer   the serializer to use for this message.
	 * @param length       The length of message if known. Usually this is provided
	 *                     when deserializing of the wire as the length will be
	 *                     provided as part of the header. If unknown then set to
	 *                     Message.UNKNOWN_LENGTH
	 */
	public static Block setBlock4(NetworkParameters params, byte[] payloadBytes, MessageSerializer serializer,
			int length) throws ProtocolException {
		return setBlock5(params, payloadBytes, 0, serializer, length);
	}

	/**
	 * Construct a block object from the Bitcoin wire format.
	 * 
	 * @param params       NetworkParameters object.
	 * @param payloadBytes the payload to extract the block from.
	 * @param offset       The location of the first payload byte within the array.
	 * @param serializer   the serializer to use for this message.
	 * @param length       The length of message if known. Usually this is provided
	 *                     when deserializing of the wire as the length will be
	 *                     provided as part of the header. If unknown then set to
	 *                     Message.UNKNOWN_LENGTH
	 */
	public static Block setBlock5(NetworkParameters params, byte[] payloadBytes, int offset,
			MessageSerializer serializer, int length) throws ProtocolException {
		Block a = new Block(params);
		a.setValues5(params, payloadBytes, offset, serializer, length);
		return a;
	}

	public boolean isBLOCKTYPE_INITIAL() {

		return getBlockType() == BlockType.BLOCKTYPE_INITIAL;

	}

	/**
	 * Parse transactions from the block.
	 * 
	 * @param transactionsOffset Offset of the transactions within the block. Useful
	 *                           for non-Bitcoin chains where the block header may
	 *                           not be a fixed size.
	 */
	protected void parseTransactions(final int transactionsOffset) throws ProtocolException {
		cursor = transactionsOffset;
		optimalEncodingMessageSize = NetworkParameters.HEADER_SIZE;
		if (payload.length == cursor) {
			// This message is just a header, it has no transactions.
			transactionBytesValid = false;
			return;
		}

		int numTransactions = (int) readVarInt();
		optimalEncodingMessageSize += VarInt.sizeOf(numTransactions);
		transactions = new ArrayList<>(numTransactions);
		for (int i = 0; i < numTransactions; i++) {
			Transaction tx = Transaction.fromTransaction6(params, payload, cursor, this, serializer, UNKNOWN_LENGTH);
			// Label the transaction as coming from the P2P network, so code
			// that cares where we first saw it knows.
			// tx.getConfidence().setSource(TransactionConfidence.Source.NETWORK);
			transactions.add(tx);
			cursor += tx.getMessageSize();
			optimalEncodingMessageSize += tx.getOptimalEncodingMessageSize();
		}
		transactionBytesValid = serializer.isParseRetainMode();
	}

	@Override
	protected void parse() throws ProtocolException {
		// header
		cursor = offset;
		version = readUint32();
		prevBlockHash = readHash();
		prevBranchBlockHash = readHash();
		merkleRoot = readHash();
		time = readInt64();
		lastMiningRewardBlock = readInt64();
		{
			String typeName = readStr();
			blockType = typeName.isEmpty() ? BlockType.BLOCKTYPE_TRANSFER : BlockType.valueOf(typeName);
		}
		height = readInt64();

		// Proposer PQ fields (always written for new blocks, backward compat for old)
		if (version > NetworkParameters.BLOCK_VERSION_GENESIS && cursor < offset + length) {
			proposerKeyBundle = readNullableBytes();
			proposerSignatureBundle = readNullableBytes();
		}

		hash = Sha256Hash.wrapReversed(Sha256Hash.hashTwice(payload, offset, cursor - offset));
		headerBytesValid = serializer.isParseRetainMode();
		// transactions
		parseTransactions(cursor);
		length = cursor - offset;
	}

	private byte[] readNullableBytes() {
		long len = readVarInt();
		if (len == 0) return null;
		byte[] data = readBytes((int) len);
		return data;
	}

	public int getOptimalEncodingMessageSize() {
		if (optimalEncodingMessageSize != 0)
			return optimalEncodingMessageSize;
		optimalEncodingMessageSize = bitcoinSerialize().length;
		return optimalEncodingMessageSize;
	}

	// default for testing
	void writeHeader(OutputStream stream) throws IOException {
		// try for cached write first
		if (headerBytesValid && payload != null && payload.length >= offset + NetworkParameters.HEADER_SIZE) {
			stream.write(payload, offset, NetworkParameters.HEADER_SIZE);
			return;
		}

		// fall back to manual write
		Utils.uint32ToByteStreamLE(version, stream);
		stream.write(prevBlockHash.getReversedBytes());
		stream.write(prevBranchBlockHash.getReversedBytes());
		stream.write(getMerkleRoot().getReversedBytes());
		Utils.int64ToByteStreamLE(time, stream);
		Utils.int64ToByteStreamLE(lastMiningRewardBlock, stream);
		{
			byte[] blockTypeName = (blockType != null ? blockType : BlockType.BLOCKTYPE_TRANSFER).name()
					.getBytes("UTF-8");
			stream.write(new VarInt(blockTypeName.length).encode());
			stream.write(blockTypeName);
		}
		Utils.int64ToByteStreamLE(height, stream);

		if (version > NetworkParameters.BLOCK_VERSION_GENESIS) {
			writeNullableBytes(stream, proposerKeyBundle);
			writeNullableBytes(stream, proposerSignatureBundle);
		}
	}

	private static void writeNullableBytes(OutputStream stream, byte[] data) throws IOException {
		if (data == null) {
			stream.write(new VarInt(0).encode());
		} else {
			stream.write(new VarInt(data.length).encode());
			stream.write(data);
		}
	}

	private void writeTransactions(OutputStream stream) throws IOException {
		// check for no transaction conditions first
		// must be a more efficient way to do this but I'm tired atm.
		if (transactions == null) {
			return;
		}

		// confirmed we must have transactions either cached or as objects.
		if (transactionBytesValid && payload != null && payload.length >= offset + length) {
			stream.write(payload, offset + NetworkParameters.HEADER_SIZE, length - NetworkParameters.HEADER_SIZE);
			return;
		}

		stream.write(new VarInt(transactions.size()).encode());
		for (Transaction tx : transactions) {
			tx.bitcoinSerialize(stream);
		}

	}

	/**
	 * Special handling to check if we have a valid byte array for both header and
	 * transactions
	 */
	@Override
	public byte[] bitcoinSerialize() {
		// we have completely cached byte array.
		if (headerBytesValid && transactionBytesValid) {
			Preconditions.checkNotNull(payload,
					"Bytes should never be null if headerBytesValid && transactionBytesValid");
			if (length == payload.length) {
				return payload;
			} else {
				// byte array is offset so copy out the correct range.
				byte[] buf = new byte[length];
				System.arraycopy(payload, offset, buf, 0, length);
				return buf;
			}
		}

		// At least one of the two cacheable components is invalid
		// so fall back to stream write since we can't be sure of the length.
		ByteArrayOutputStream stream = new UnsafeByteArrayOutputStream(
				length == UNKNOWN_LENGTH ? NetworkParameters.HEADER_SIZE + guessTransactionsLength() : length);
		try {
			writeHeader(stream);
			writeTransactions(stream);
		} catch (IOException e) {
			// Cannot happen, we are serializing to a memory stream.
		}
		return stream.toByteArray();
	}

	@Override
	protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
		writeHeader(stream);
		writeTransactions(stream);
	}

	/**
	 * Provides a reasonable guess at the byte length of the transactions part of
	 * the block. The returned value will be accurate in 99% of cases and in those
	 * cases where not will probably slightly oversize. This is used to preallocate
	 * the underlying byte array for a ByteArrayOutputStream. If the size is under
	 * the real value the only penalty is resizing of the underlying byte array.
	 */
	private int guessTransactionsLength() {
		if (transactionBytesValid)
			return payload.length - NetworkParameters.HEADER_SIZE;
		if (transactions == null)
			return 0;
		int len = VarInt.sizeOf(transactions.size());
		for (Transaction tx : transactions) {
			// 255 is just a guess at an average tx length
			len += tx.length == UNKNOWN_LENGTH ? 255 : tx.length;
		}
		return len;
	}

	@Override
	protected void unCache() {
		// Since we have alternate uncache methods to use internally this will
		// only ever be called by a child
		// transaction so we only need to invalidate that part of the cache.
		unCacheTransactions();
	}

	private void unCacheHeader() {
		headerBytesValid = false;
		if (!transactionBytesValid)
			payload = null;
		hash = null;
	}

	private void unCacheTransactions() {
		transactionBytesValid = false;
		if (!headerBytesValid)
			payload = null;
		// Current implementation has to uncache headers as well as any change
		// to a tx will alter the merkle root. In
		// future we can go more granular and cache merkle root separately so
		// rest of the header does not need to be
		// rewritten.
		unCacheHeader();
		// Clear merkleRoot last as it may end up being parsed during
		// unCacheHeader().
		merkleRoot = null;
	}

	/**
	 * Calculates the block hash by serializing the block and hashing the resulting
	 * bytes.
	 */
	private Sha256Hash calculateHash() {
		try {
			ByteArrayOutputStream bos = new UnsafeByteArrayOutputStream(NetworkParameters.HEADER_SIZE);
			writeHeader(bos);
			return Sha256Hash.wrapReversed(Sha256Hash.hashTwice(bos.toByteArray()));
		} catch (IOException e) {
			throw new RuntimeException(e); // Cannot happen.
		}
	}

	/** Returns the hash as hex string. */
	public String getHashAsString() {
		return getHash().toString();
	}

	/**
	 * Returns the hash of the block (which for a valid, solved block should be
	 * below the target). Big endian.
	 */
	@Override
	public Sha256Hash getHash() {
		if (hash == null)
			hash = calculateHash();
		return hash;
	}

	/** Returns a copy of the block */
	public Block cloneAsHeader() {
		Block block = Block.setBlock2(params, NetworkParameters.BLOCK_VERSION_GENESIS);
		copyBitcoinHeaderTo(block);
		return block;
	}

	/** Copy the block into the provided block. */
	protected final void copyBitcoinHeaderTo(final Block block) {
		block.prevBlockHash = prevBlockHash;
		block.prevBranchBlockHash = prevBranchBlockHash;
		block.merkleRoot = getMerkleRoot();
		block.version = version;
		block.time = time;
		block.lastMiningRewardBlock = lastMiningRewardBlock;

		block.blockType = blockType;
		block.proposerKeyBundle = proposerKeyBundle != null ? Arrays.copyOf(proposerKeyBundle, proposerKeyBundle.length) : null;
		block.proposerSignatureBundle = proposerSignatureBundle != null ? Arrays.copyOf(proposerSignatureBundle, proposerSignatureBundle.length) : null;
		block.transactions = null;
		block.hash = getHash();
	}

	/**
	 * Returns a multi-line string containing a description of the contents of the
	 * block. Use for debugging purposes only.
	 */
	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("   hash: ").append(getHashAsString()).append('\n');
		s.append("   version: ").append(version);
		s.append("   time: ").append(time).append(" (").append(Utils.dateTimeFormat(time * 1000)).append(")\n");
		s.append("   height: ").append(height).append("\n");
		s.append("   chain length: ").append(getLastMiningRewardBlock()).append("\n");
		s.append("   previous: ").append(getPrevBlockHash()).append("\n");
		s.append("   branch: ").append(getPrevBranchBlockHash()).append("\n");
		s.append("   merkle: ").append(getMerkleRoot()).append("\n");
		s.append("   blocktype: ").append(blockType).append("\n");
		if (transactions != null && !transactions.isEmpty()) {
			s.append("   ").append(transactions.size()).append(" transaction(s):\n");
			for (Transaction tx : transactions) {
				s.append(tx);
			}
		}
		if (blockType == BlockType.BLOCKTYPE_BEACON) {
			try {
				if (transactions != null && !transactions.isEmpty()) {
					RewardInfo rewardInfo = new RewardInfo().parse(getTransactions().get(0).getData());
					s.append(rewardInfo.toString());
				}
			} catch (Exception e) {
				// ignore throw new RuntimeException(e);
			}
		}
		if (blockType == BlockType.BLOCKTYPE_ORDER_OPEN) {

			try {
				OrderOpenInfo info = new OrderOpenInfo().parse(transactions.get(0).getData());
				s.append(info.toString());
			} catch (Exception e) {
				// ignore throw new RuntimeException(e);
			}
		}
		if (blockType == BlockType.BLOCKTYPE_TOKEN_CREATION) {

			try {
				TokenInfo info = new TokenInfo().parse(transactions.get(0).getData());
				s.append(info.toString());
			} catch (Exception e) {
				// ignore throw new RuntimeException(e);
			}

		}
		return s.toString();
	}

	private void checkTimestamp() throws VerificationException {
		// Allow injection of a fake clock to allow unit testing.
		long currentTime = Utils.currentTimeSeconds();
		if (time > currentTime + NetworkParameters.ALLOWED_TIME_DRIFT)
			throw new TimeTravelerException();
	}

	private void checkSigOps() throws VerificationException {
		// Check there aren't too many signature verifications in the block.
		// This is an anti-DoS measure, see the
		// comments for MAX_BLOCK_SIGOPS.
		int sigOps = 0;
		if (transactions == null)
			return;
		for (Transaction tx : transactions) {
			sigOps += tx.getSigOpCount();
		}
		if (sigOps > NetworkParameters.MAX_BLOCK_SIGOPS)
			throw new SigOpsException();
	}

	private void checkMerkleRoot() throws VerificationException {
		Sha256Hash calculatedRoot = calculateMerkleRoot();
		if (!calculatedRoot.equals(merkleRoot)) {
			log.error("Merkle tree did not verify");
			throw new MerkleRootMismatchException();
		}
	}

	private Sha256Hash calculateMerkleRoot() {
		List<byte[]> tree = buildMerkleTree();
		if (tree.isEmpty())
			return Sha256Hash.ZERO_HASH;
		return Sha256Hash.wrap(tree.get(tree.size() - 1));
	}

	private List<byte[]> buildMerkleTree() {
		// The Merkle root is based on a tree of hashes calculated from the
		// transactions:
		//
		// root
		// / \
		// A B
		// / \ / \
		// t1 t2 t3 t4
		//
		// The tree is represented as a list: t1,t2,t3,t4,A,B,root where each
		// entry is a hash.
		//
		// The hashing algorithm is double SHA-256. The leaves are a hash of the
		// serialized contents of the transaction.
		// The interior nodes are hashes of the concenation of the two child
		// hashes.
		//
		// This structure allows the creation of proof that a transaction was
		// included into a block without having to
		// provide the full block contents. Instead, you can provide only a
		// Merkle branch. For example to prove tx2 was
		// in a block you can just provide tx2, the hash(tx1) and B. Now the
		// other party has everything they need to
		// derive the root, which can be checked against the block header. These
		// proofs aren't used right now but
		// will be helpful later when we want to download partial block
		// contents.
		//
		// Note that if the number of transactions is not even the last tx is
		// repeated to make it so (see
		// tx3 above). A tree with 5 transactions would look like this:
		//
		// root
		// / \
		// 1 5
		// / \ / \
		// 2 3 4 4
		// / \ / \ / \
		// t1 t2 t3 t4 t5 t5
		ArrayList<byte[]> tree = new ArrayList<>();
		if (transactions == null)
			transactions = new ArrayList<>();
		// Start by adding all the hashes of the transactions as leaves of the
		// tree.
		for (Transaction t : transactions) {
			tree.add(t.getHash().getBytes());
		}
		int levelOffset = 0; // Offset in the list where the currently processed
								// level starts.
		// Step through each level, stopping when we reach the root (levelSize
		// == 1).
		for (int levelSize = transactions.size(); levelSize > 1; levelSize = (levelSize + 1) / 2) {
			// For each pair of nodes on that level:
			for (int left = 0; left < levelSize; left += 2) {
				// The right hand node can be the same as the left hand, in the
				// case where we don't have enough
				// transactions.
				int right = Math.min(left + 1, levelSize - 1);
				byte[] leftBytes = Utils.reverseBytes(tree.get(levelOffset + left));
				byte[] rightBytes = Utils.reverseBytes(tree.get(levelOffset + right));
				tree.add(Utils.reverseBytes(hashTwice(leftBytes, 0, 32, rightBytes, 0, 32)));
			}
			// Move to the next level.
			levelOffset += levelSize;
		}
		return tree;
	}

	/**
	 * Checks the block data to ensure it follows the rules laid out in the network
	 * parameters. Specifically throws an exception if the timestamp is too far
	 * from what it should be. This is <b>not</b> everything that is required for
	 * a block to be valid, only what is checkable independent of the chain and
	 * without a transaction index.
	 *
	 */
	public void verifyHeader() throws VerificationException {
		checkTimestamp();
		if (version > NetworkParameters.BLOCK_VERSION_GENESIS) {
			if (proposerKeyBundle == null || proposerSignatureBundle == null)
				throw new VerificationException("Block proposer PQ fields required at version " + version);
			if (!verifyProposer())
				throw new VerificationException("Block proposer PQ signature verification failed");
		}
	}

	/**
	 * Verify the proposer's post-quantum dual signature.
	 * Breaks the circular dependency by signing the header hash
	 * with the proposer signature field excluded.
	 */
	public boolean verifyProposer() {
		if (proposerKeyBundle == null || proposerSignatureBundle == null) {
			log.debug("No proposer PQ sigs to verify (version={})", version);
			return true;
		}
		try {
			KeyBundle keys = KeyBundle.deserialize(proposerKeyBundle);
			SignatureBundle sigs = SignatureBundle.deserialize(proposerSignatureBundle);
			byte[] signingHash = computeProposerSigningHash();
			return PQScriptUtils.verifyProposerSignature(keys, sigs, signingHash);
		} catch (Exception e) {
			log.warn("Block proposer verification failed: {}", e.getMessage());
			return false;
		}
	}

	/** Hash of the header including proposerKeyBundle but excluding proposerSignatureBundle
	 *  (signature excluded to break the circular signing dependency). */
	private byte[] computeProposerSigningHash() {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			Utils.uint32ToByteStreamLE(version, bos);
			bos.write(prevBlockHash.getReversedBytes());
			bos.write(prevBranchBlockHash.getReversedBytes());
			bos.write(getMerkleRoot().getReversedBytes());
			Utils.int64ToByteStreamLE(time, bos);
			Utils.int64ToByteStreamLE(lastMiningRewardBlock, bos);
			byte[] blockTypeName = (blockType != null ? blockType : BlockType.BLOCKTYPE_TRANSFER).name()
					.getBytes("UTF-8");
			bos.write(new VarInt(blockTypeName.length).encode());
			bos.write(blockTypeName);
			Utils.int64ToByteStreamLE(height, bos);
			// Include proposerKeyBundle (data, no circular dependency).
			// proposerSignatureBundle is EXCLUDED — the signature cannot sign itself.
			if (version > NetworkParameters.BLOCK_VERSION_GENESIS) {
				writeNullableBytes(bos, proposerKeyBundle);
			}
			return Sha256Hash.hashTwice(bos.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Checks the block contents formally
	 *
	 * @throws VerificationException if there was an error verifying the block.
	 */
	public void verifyTransactions() throws VerificationException {
		// Now we need to check that the body of the block actually matches the
		// headers. The network won't generate
		// an invalid block, but if we didn't validate this then an untrusted
		// man-in-the-middle could obtain the next
		// valid block from the network and simply replace the transactions in
		// it with their own fictional
		// transactions that reference spent or non-existant inputs.
		if (this.getOptimalEncodingMessageSize() > getMaxBlockSize())
			throw new LargerThanMaxBlockSize();
		checkMerkleRoot();
		checkSigOps();
		if (transactions == null)
			return;
		for (Transaction transaction : transactions) {
			if (!allowCoinbaseTransaction() && transaction.isCoinBase()) {
				throw new CoinbaseDisallowedException();
			}

			transaction.verify();
		}
	}

	private int getMaxBlockSize() {
		return blockType.getMaxBlockSize();
	}

	/**
	 * Verifies both the header and that the transactions hash to the merkle root.
	 *
	 */
	public void verify() throws VerificationException {
		verifyHeader();
		verifyTransactions();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		return getHash().equals(((Block) o).getHash());
	}

	@Override
	public int hashCode() {
		return getHash().hashCode();
	}

	/**
	 * Returns the merkle root in big endian form, calculating it from transactions
	 * if necessary.
	 */
	public Sha256Hash getMerkleRoot() {
		if (merkleRoot == null) {
			unCacheHeader();
			merkleRoot = calculateMerkleRoot();
		}
		return merkleRoot;
	}

	/** Exists only for unit testing. */
	void setMerkleRoot(Sha256Hash value) {
		unCacheHeader();
		merkleRoot = value;
		hash = null;
	}

	/**
	 * Adds a transaction to this block. The nonce and merkle root are invalid after
	 * this.
	 */
	public void addTransaction(Transaction t) {
		unCacheTransactions();
		if (transactions == null) {
			transactions = new ArrayList<>();
		}
		t.setParent(this);
		// cui
		transactions.add(t);
		adjustLength(transactions.size(), t.length);
		// Force a recalculation next time the values are needed.
		merkleRoot = null;
		hash = null;
	}

	/**
	 * Returns the version of the block data structure as defined by the Bitcoin
	 * protocol.
	 */
	public long getVersion() {
		return version;
	}

	/**
	 * Returns the hash of the previous trunk block in the chain, as defined by the
	 * block header.
	 */
	public Sha256Hash getPrevBlockHash() {
		return prevBlockHash;
	}

	public void setPrevBlockHash(Sha256Hash prevBlockHash) {
		unCacheHeader();
		this.prevBlockHash = prevBlockHash;
		this.hash = null;
	}

	/**
	 * Returns the hash of the previous branch block in the chain, as defined by the
	 * block header.
	 */
	public Sha256Hash getPrevBranchBlockHash() {
		return prevBranchBlockHash;
	}

	public void setPrevBranchBlockHash(Sha256Hash prevBranchBlockHash) {
		unCacheHeader();
		this.prevBranchBlockHash = prevBranchBlockHash;
		this.hash = null;
	}

	/**
	 * Returns the time at which the block was solved and broadcast, according to
	 * the clock of the solving node. This is measured in seconds since the UNIX
	 * epoch (midnight Jan 1st 1970).
	 */
	public long getTimeSeconds() {
		return time;
	}

	/**
	 * Returns the time at which the block was solved and broadcast, according to
	 * the clock of the solving node.
	 */
	public Date getTime() {
		return new Date(getTimeSeconds() * 1000);
	}

	public void setTime(long time) {
		unCacheHeader();
		this.time = time;
		this.hash = null;
	}

	/**
	 * Returns an immutable list of transactions held in this block, or null if this
	 * object represents just a header.
	 */
	// return new List<> to avoid check null @Nullable
	public List<Transaction> getTransactions() {
		return transactions == null ? new ArrayList<>() : ImmutableList.copyOf(transactions);
	}

	// ///////////////////////////////////////////////////////////////////////////////////////////////
	// Unit testing related methods.

	// Used to make transactions unique.
	private static int txCounter;

	public void addCoinbaseTransaction(byte[] pubKeyTo, Coin value, TokenInfo tokenInfo, MemoInfo memoInfo) {
		unCacheTransactions();
		transactions = new ArrayList<>();

		Transaction coinbase = new Transaction(params);
		if (tokenInfo != null) {
			coinbase.setDataClassName(DataClassName.TOKEN.name());
			byte[] buf = tokenInfo.toByteArray();
			coinbase.setData(buf);
		}
		coinbase.setMemo(memoInfo);
		// coinbase.tokenid = value.tokenid;
		final ScriptBuilder inputBuilder = new ScriptBuilder();

		inputBuilder.data(new byte[] { (byte) txCounter, (byte) (txCounter++ >> 8) });

		// A real coinbase transaction has some stuff in the scriptSig like the
		// extraNonce and difficulty. The
		// transactions are distinguished by every TX output going to a
		// different key.
		//
		// Here we will do things a bit differently so a new address isn't
		// needed every time. We'll put a simple
		// counter in the scriptSig so every transaction has a different hash.
		coinbase.addInput(TransactionInput.fromScriptBytes(params, coinbase, inputBuilder.build().getProgram()));
		if (tokenInfo == null) {
			coinbase.addOutput(new TransactionOutput(params, coinbase, value,
					ScriptBuilder.createOutputScript(ECKey.fromPublicOnly(pubKeyTo)).getProgram()));
		} else {

			if (tokenInfo.getToken() == null || tokenInfo.getToken().getSignnumber() == 0) {
				coinbase.addOutput(new TransactionOutput(params, coinbase, value,
						ScriptBuilder.createOutputScript(ECKey.fromPublicOnly(pubKeyTo)).getProgram()));

			} else {

				List<ECKey> keys = new ArrayList<>();
				for (MultiSignAddress multiSignAddress : tokenInfo.getMultiSignAddresses()) {
					if (multiSignAddress.getTokenHolder() == 1) {
						ECKey ecKey = ECKey.fromPublicOnly(Utils.HEX.decode(multiSignAddress.getPubKeyHex()));
						keys.add(ecKey);
					}
				}
				// TODO m:n signs
				if (keys.size() <= 1) {
					coinbase.addOutput(new TransactionOutput(params, coinbase, value,
							ScriptBuilder.createOutputScript(ECKey.fromPublicOnly(pubKeyTo)).getProgram()));
				} else {
					int n = keys.size();
					Script scriptPubKey = ScriptBuilder.createMultiSigOutputScript(n, keys);
					coinbase.addOutput(new TransactionOutput(params, coinbase, value, scriptPubKey.getProgram()));
				}
			}
		}
		transactions.add(coinbase);
		coinbase.setParent(this);
		coinbase.length = coinbase.unsafeBitcoinSerialize().length;
		adjustLength(transactions.size(), coinbase.length);
	}

	public boolean allowCoinbaseTransaction() {
		return blockType.allowCoinbaseTransaction();
	}

	/**
	 * Return whether this block contains any transactions.
	 * 
	 * @return true if the block contains transactions, false otherwise (is purely a
	 *         header).
	 */
	public boolean hasTransactions() {
		return this.transactions != null && !this.transactions.isEmpty();
	}

	public BlockType getBlockType() {
		return blockType;
	}

	@Nullable
	public byte[] getProposerKeyBundle() { return proposerKeyBundle; }

	public void setProposerKeyBundle(@Nullable byte[] proposerKeyBundle) {
		this.proposerKeyBundle = proposerKeyBundle;
	}

	@Nullable
	public byte[] getProposerSignatureBundle() { return proposerSignatureBundle; }

	public void setProposerSignatureBundle(@Nullable byte[] proposerSignatureBundle) {
		this.proposerSignatureBundle = proposerSignatureBundle;
	}

	public void setBlockType(long blocktype) {
		blockType = BlockType.values()[(int) blocktype];
		setBlockType(blockType);
	}

	public void setBlockType(BlockType blocktype) {
		unCacheHeader();
		this.blockType = blocktype;
		this.hash = null;
	}

	public long getLastMiningRewardBlock() {
		return lastMiningRewardBlock;
	}

	public void setLastMiningRewardBlock(long lastMiningRewardBlock) {
		this.lastMiningRewardBlock = lastMiningRewardBlock;
	}

	public long getHeight() {
		return height;
	}

	public void setHeight(long height) {
		unCacheHeader();
		this.height = height;
		this.hash = null;

	}

}
