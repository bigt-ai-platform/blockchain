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
package net.bigtangle.params;

import static net.bigtangle.core.Utils.HEX;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import jakarta.annotation.Nullable;

import com.google.common.base.Objects;
import com.google.common.math.LongMath;

import net.bigtangle.core.BitcoinSerializer;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.MessageSerializer;
import net.bigtangle.core.PermissionDomainname;
import net.bigtangle.script.Script;

/**
 * <p>
 * NetworkParameters contains the data needed for working with an instantiation
 * of a BigTangle.
 * </p>
 *
 * <p>
 * This is an abstract class, concrete instantiations can be found in the params
 * package. There are two: one for the main network ({@link MainNetParams}), one
 * for the public test network. Although this class contains some aliases for
 * them, you are encouraged to call the static get() methods on each specific
 * params class directly.
 * </p>
 */
public abstract class NetworkParameters {

	/**
	 * The string returned by getId() for the main, production network where people
	 * trade things.
	 */
	public static final String ID_MAINNET = "Mainnet";

	/** Unit test network. */
	public static final String ID_UNITTESTNET = "Test";

	protected int addressHeader;
	protected int p2shHeader;
	protected int dumpedPrivateKeyHeader;
	protected int[] acceptableAddressCodes;
	protected int bip32HeaderPub;
	protected int bip32HeaderPriv;
	protected long packetMagic; // Indicates message origin network and is used
	// to seek to the next message when stream state
	// is unknown.
    protected byte[] alertSigningKey;
	/**
	 * See getId(). This may be null for old deserialized wallets. In that case we
	 * derive it heuristically by looking at the port number.
	 */
	protected String id;

	/**
	 * The depth of blocks required for a coinbase transaction to be spendable.
	 */
	protected int spendableCoinbaseDepth;

	protected String[] dnsSeeds;

	protected transient MessageSerializer defaultSerializer = null;

	public String genesisPub;
	// List of root permissionDomainname
	protected List<String> permissionDomainname;

	/**
	 * Identifier of the chain these parameters belong to. Layer 0 (the
	 * settlement chain) is "L0"; each Layer 1 sub-chain (ordermatch, contract,
	 * ...) gets its own id. Used to scope block storage, validation and
	 * discovery to a single chain. See LAYERING-PLAN.md.
	 */
	protected String chainId = "L0";

	/** Post-quantum suite governance: suiteId -> activation chain height
	 *  (0 = active from genesis).  A suite without an entry is never active.
	 *  Governance activates a suite by recording its activation height and
	 *  sunsets it by removing the entry. */
	protected final java.util.Map<Integer, Long> pqSuiteActivation = new java.util.HashMap<>();

	/** @return immutable copy of the configured PQ suite IDs (those with an activation height). */
	public List<Integer> getPqSuites() {
		return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(pqSuiteActivation.keySet()));
	}

	/** Activate a suite from genesis (height 0). */
	public void addPqSuite(int suiteId) { pqSuiteActivation.put(suiteId, 0L); }
	/** Remove a suite from the governance table (sunsets it). */
	public void removePqSuite(int suiteId) { pqSuiteActivation.remove(Integer.valueOf(suiteId)); }
	/** Activation height of a suite, or -1 if it is never activated. */
	public long getPqSuiteActivationHeight(int suiteId) {
		return pqSuiteActivation.getOrDefault(suiteId, -1L);
	}
	/** Record the chain height at which a suite becomes active. */
	public void setPqSuiteActivationHeight(int suiteId, long height) {
		pqSuiteActivation.put(suiteId, height);
	}
	public boolean isPqEnabled() { return !pqSuiteActivation.isEmpty(); }
	/** True if the suite is configured to activate at some height. */
	public boolean isPqSuiteActive(int suiteId) { return pqSuiteActivation.containsKey(suiteId); }
	/** True if the suite is active at or before the given chain height. */
	public boolean isPqSuiteActive(int suiteId, long height) {
		Long h = pqSuiteActivation.get(suiteId);
		return h != null && height >= h.longValue();
	}

	/**
	 * The set of {@link BlockType}s that a node running these parameters will
	 * accept. Layer 0 accepts the full settlement set; a Layer 1 sub-chain
	 * accepts only the types that belong to it. Enforced in
	 * {@code ServiceBaseCheck.checkBlockBeforeSave} so a node never ingests a
	 * block type from another layer.
	 */
	public EnumSet<BlockType> getAllowedBlockTypes() {
		return EnumSet.allOf(BlockType.class);
	}

	/** The chain id (e.g. "L0", "ordermatch"). Never null. */
	public String getChainId() {
		return chainId;
	}

	/** Whether this chain's genesis block should mint the native BIG token.
	 *  Only Layer 0 should mint BIG. L1 chains get BIG via bridge peg only. */
	public boolean genesisMintsBIG() {
		return true;
	}

	// MCMC settings
	public static final int CONFIRMATION_UPPER_THRESHOLD_PERCENT = 51;
	public static final int CONFIRMATION_LOWER_THRESHOLD_PERCENT = 45;
	public static final int NUMBER_RATING_TIPS = 10;
	public static final int CONFIRMATION_UPPER_THRESHOLD = CONFIRMATION_UPPER_THRESHOLD_PERCENT * NUMBER_RATING_TIPS
			/ 100;
	public static final int CONFIRMATION_LOWER_THRESHOLD = CONFIRMATION_LOWER_THRESHOLD_PERCENT * NUMBER_RATING_TIPS
			/ 100;

	// Token ID for System Coin
	public static final String BIGTANGLE_TOKENID_STRING = "bc";
	public static final byte[] BIGTANGLE_TOKENID = HEX.decode(BIGTANGLE_TOKENID_STRING);
	public static final String BIGTANGLE_TOKENNAME = "BIG";
	public static final int BIGTANGLE_DECIMAL = 6;

	/**
	 * The version number at the start of the network.
	 */
	public static final long BLOCK_VERSION_GENESIS = 1;

	/**
	 * A constant shared by the entire network: how large in bytes a block is
	 * allowed to be. It can no be smaller than last value, it will break consensus
	 * history.
	 * <p>
	 * Start at: 262144
	 */
	public static int MAX_DEFAULT_BLOCK_SIZE = 20 * 1024 * 1024; // 5MB, adjustable for testing

	/**
	 * A "sigop" is a signature verification operation. Because they're expensive we
	 * also impose a separate limit on the number in a block to prevent somebody
	 * mining a huge block that has way more sigops than normal, so is very
	 * expensive/slow to verify.
	 */
	public static final int MAX_BLOCK_SIGOPS = MAX_DEFAULT_BLOCK_SIZE / 50;

	/**
	 * The maximum allowed time drift of blocks into the future in seconds.
	 */
	public static final long ALLOWED_TIME_DRIFT = 5 * 60;

	public static final int HEADER_SIZE = 72 // version + prevBlockHash + prevBranchHash + merkleRoot + time
			+ 32 // additional branch prev block
			+ 4 // time expanded from int to long
			+ 8 // lastMiningRewardBlock
			+ 4 // blockType
			+ 8 // height
			;
	// max time of an order in seconds
	public static final long ORDER_TIMEOUT_MAX = 8 * 60 * 60;

	// 10^17 BIG total supply (10^(11 + 6 decimals))
	public static BigInteger BigtangleCoinTotal = BigInteger.valueOf(LongMath.pow(10, 11 + BIGTANGLE_DECIMAL));

	// PoS epoch configuration
	public static final long SLOTS_PER_EPOCH = 32L;

	// Reward-chainlength at which every beacon MUST carry SlotData with a valid
	// proposer signature + RANDAO reveal. Below this height legacy beacons
	// without SlotData are tolerated (test/pre-PoS chains); at or above it an
	// unauthenticated beacon is rejected outright.
	public static final long POS_BEACON_SLOTDATA_ACTIVATION = 1024L;

	// Max blocks per reward chainlength
	public static final int TARGET_MAX_BLOCKS_IN_REWARD = 5000;
	public static final int MAX_REWARD_BLOCK_SIZE = MAX_DEFAULT_BLOCK_SIZE + TARGET_MAX_BLOCKS_IN_REWARD * 200;

	// MCMC horizon: look back up to this many confirmed reward blocks
	public static final int CHAINLENGTH_CUTOFF = 40;
	// MCMC forward horizon: look ahead up to this many blocks above confirmed reward
	public static final int FORWARD_BLOCK_HORIZON = TARGET_MAX_BLOCKS_IN_REWARD / 4;

	protected NetworkParameters() {
	}

	/**
	 * A Java package style string acting as unique ID for these parameters
	 */
	public String getId() {
		return id;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		return getId().equals(((NetworkParameters) o).getId());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(getId());
	}

	/**
	 * Returns the network parameters for the given string ID or NULL if not
	 * recognized.
	 */
	@Nullable
	public static NetworkParameters fromID(String id) {
		if (id.equals(ID_MAINNET)) {
			return MainNetParams.get();
		} else if (id.equals(ID_UNITTESTNET)) {
			return MainNetParams.get();
		} else {
			return null;
		}
	}

	public int getSpendableCoinbaseDepth() {
		return spendableCoinbaseDepth;
	}

	/**
	 * Returns DNS names that when resolved, give IP addresses of active peers.
	 */
	public String[] getDnsSeeds() {
		return dnsSeeds;
	}

	/**
	 * First byte of a base58 encoded P2SH address. P2SH addresses are defined as
	 * part of BIP0013.
	 */
	public int getP2SHHeader() {
		return p2shHeader;
	}

	/**
	 * First byte of a base58 encoded dumped (WIF) private key. See
	 * {@link net.bigtangle.utils.DumpedPrivateKey}.
	 */
	public int getDumpedPrivateKeyHeader() {
		return dumpedPrivateKeyHeader;
	}

	/** The header bytes that identify the start of a packet on this network. */
	public long getPacketMagic() {
		return packetMagic;
	}

	/** Returns the 4 byte header for BIP32 (HD) wallet - public key part. */
	public int getBip32HeaderPub() {
		return bip32HeaderPub;
	}

	/** Returns the 4 byte header for BIP32 (HD) wallet - private key part. */
	public int getBip32HeaderPriv() {
		return bip32HeaderPriv;
	}

	// public abstract Coin getMaxMoney();

	/**
	 * Return the default serializer for this network. This is a shared serializer.
	 *
	 */
	public final synchronized MessageSerializer getDefaultSerializer() {
		// Construct a default serializer if we don't have one
		if (null == this.defaultSerializer) {
			// Don't grab a lock unless we absolutely need it
			synchronized (this) {
				// Now we have a lock, double check there's still no serializer
				// and create one if so.
				if (null == this.defaultSerializer) {
					// As the serializers are intended to be immutable, creating
					// two due to a race condition should not be a problem,
					// however
					// to be safe we ensure only one exists for each network.
					this.defaultSerializer = getSerializer(false);
				}
			}
		}
		return defaultSerializer;
	}

	public BitcoinSerializer getSerializer(boolean parseRetain) {
		return new BitcoinSerializer(this, parseRetain);
	}

	/**
	 * The flags indicating which script validation tests should be applied to the
	 * given transaction. Enables support for alternative blockchains which enable
	 * tests based on different criteria.
	 *
	 */
	public EnumSet<Script.VerifyFlag> getTransactionVerificationFlags() {
		final EnumSet<Script.VerifyFlag> verifyFlags = EnumSet.noneOf(Script.VerifyFlag.class);
		// if (block.getTimeSeconds() >= NetworkParameters.BIP16_ENFORCE_TIME)
		verifyFlags.add(Script.VerifyFlag.P2SH);

		// Start enforcing CHECKLOCKTIMEVERIFY, (BIP65) for block.nVersion=4
		// blocks, when 75% of the network has upgraded:

		verifyFlags.add(Script.VerifyFlag.CHECKLOCKTIMEVERIFY);

		return verifyFlags;
	}

	// initial server seeds to start, those server can register new servers and
	// return other servers
	public abstract String[] serverSeeds();

	public List<PermissionDomainname> getPermissionDomainnameList() {
		ArrayList<PermissionDomainname> rootPermission = new ArrayList<>();
		for (String s : permissionDomainname) {
			rootPermission.add(new PermissionDomainname(s, ""));
		}
		return rootPermission;
	}

	/*
	 * Order Price is in orderBaseToken and is used as Long, to enable the
	 * representation of value smaller than the unit of orderBaseToken this factor
	 * is used to shift the small price into long value. It can not be changed after
	 * the initial set.
	 */
	public Integer getOrderPriceShift(String orderBaseTokens) {
		if (BIGTANGLE_TOKENID_STRING.equals(orderBaseTokens)) {
			return 0;
		} else {
			return 6;
		}
	}

	public int getProtocolVersionNum(final ProtocolVersion version) {
		return version.getBitcoinProtocolVersion();
	}

	public String getGenesisPub() {
		return genesisPub;
	}

	public int getAddressHeader() {
		return addressHeader;
	}

	public int[] getAcceptableAddressCodes() {
		return acceptableAddressCodes;
	}

	public byte[] getAlertSigningKey() {
		return alertSigningKey;
	}

 
 
}
