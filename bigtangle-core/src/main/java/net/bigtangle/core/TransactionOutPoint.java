/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2011 Google Inc.
 * Copyright 2015 Andreas Schildbach
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Objects;

import net.bigtangle.exception.ProtocolException;
import net.bigtangle.exception.ScriptException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.wallet.KeyBag;
import net.bigtangle.wallet.RedeemData;

/**
 * <p>
 * This message is a reference or pointer to an output of a different
 * transaction.
 * </p>
 * 
 * <p>
 * Instances of this class are not safe for use by multiple threads.
 * </p>
 */
public class TransactionOutPoint extends ChildMessage {

	static final int MESSAGE_LENGTH = 4 + 32 + 32;

	/** Hash of the block to which we refer. */
	private Sha256Hash blockHash;
	/** Hash of the transaction to which we refer. */
	private Sha256Hash txHash;
	/** Which output of that transaction we are talking about. */
	private long index;

	// This is not part of bitcoin serialization. It points to the connected
	// transaction.
	Transaction fromTx;

	// The connected output.
	public TransactionOutput connectedOutput = null;

	public static TransactionOutPoint fromTx(NetworkParameters params, long index, @Nullable Sha256Hash blockHash,
			@Nullable Transaction fromTx) {
		TransactionOutPoint a = new TransactionOutPoint(params, index, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH);

		if (fromTx != null && blockHash != null) {
			a.blockHash = blockHash;
			a.txHash = fromTx.getHash();
			a.fromTx = fromTx;
		}
		a.length = MESSAGE_LENGTH;
		return a;
	}

	public TransactionOutPoint(NetworkParameters params, long index, Sha256Hash blockHash, Sha256Hash transactionHash) {
		super(params);
		this.index = index;
		this.blockHash = blockHash;
		this.txHash = transactionHash;
		this.length = MESSAGE_LENGTH;
	}

	public static TransactionOutPoint fromOutput(NetworkParameters params, @Nullable Sha256Hash blockHash,
			TransactionOutput connectedOutput) {
		TransactionOutPoint a = new TransactionOutPoint(params, connectedOutput.getIndex(), blockHash,
				connectedOutput.getParentTransactionHash());
		a.connectedOutput = connectedOutput;
		return a;
	}

	/**
	 * /** Deserializes the message. This is usually part of a transaction message.
	 */
	public TransactionOutPoint(NetworkParameters params, byte[] payload, int offset) throws ProtocolException {
		super(params, payload, offset);
	}

	/**
	 * Deserializes the message. This is usually part of a transaction message.
	 * 
	 * @param params     NetworkParameters object.
	 * @param offset     The location of the first payload byte within the array.
	 * @param serializer the serializer to use for this message.
	 * @throws ProtocolException
	 */
	public TransactionOutPoint(NetworkParameters params, byte[] payload, int offset, Message parent,
			MessageSerializer serializer) throws ProtocolException {
		super(params, payload, offset, parent, serializer, MESSAGE_LENGTH);
	}

	@Override
	protected void parse() throws ProtocolException {
		length = MESSAGE_LENGTH;
		blockHash = readHash();
		txHash = readHash();
		index = readUint32();
		// length += 4;
		// if (readUint32() == 1) {
		// this.connectedOutput = new TransactionOutput(params, (Transaction)
		// this.parent, payload, cursor);
		// cursor += this.connectedOutput.getMessageSize();
		// length += this.connectedOutput.getMessageSize();
		// }
	}

	@Override
	protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
		stream.write(blockHash.getReversedBytes());
		stream.write(txHash.getReversedBytes());
		Utils.uint32ToByteStreamLE(index, stream);
		// Utils.uint32ToByteStreamLE(this.connectedOutput != null ? 1 : 0,
		// stream);
		// if (this.connectedOutput != null) {
		// this.connectedOutput.bitcoinSerializeToStream(stream);
		// }
	}

	/**
	 * An outpoint is a part of a transaction input that points to the output of
	 * another transaction. If we have both sides in memory, and they have been
	 * linked together, this returns a pointer to the connected output, or null if
	 * there is no such connection.
	 */
	@Nullable
	public TransactionOutput getConnectedOutput() {
		if (fromTx != null) {
			return fromTx.getOutputs().get((int) index);
		} else if (connectedOutput != null) {
			return connectedOutput;
		}
		return null;
	}

	/**
	 * Returns the pubkey script from the connected output.
	 * 
	 * @throws java.lang.NullPointerException if there is no connected output.
	 */
	public byte[] getConnectedPubKeyScript() {
		byte[] result = checkNotNull(getConnectedOutput()).getScriptBytes();
		checkState(result.length > 0);
		return result;
	}

	/**
	 * Returns the ECKey identified in the connected output, for either
	 * pay-to-address scripts or pay-to-key scripts. For P2SH scripts you can use
	 * {@link #getConnectedRedeemData(net.bigtangle.wallet.KeyBag)} and then get the
	 * key from RedeemData. If the script form cannot be understood, throws
	 * ScriptException.
	 *
	 * @return an ECKey or null if the connected key cannot be found in the wallet.
	 */
	@Nullable
	public ECKey getConnectedKey(KeyBag keyBag) throws ScriptException {
		TransactionOutput connectedOutput = getConnectedOutput();
		checkNotNull(connectedOutput, "Input is not connected so cannot retrieve key");
		Script connectedScript = connectedOutput.getScriptPubKey();
		if (connectedScript.isSentToAddress()) {
			byte[] addressBytes = connectedScript.getPubKeyHash();
			return keyBag.findKeyFromPubHash(addressBytes);
		} else if (connectedScript.isSentToRawPubKey()) {
			byte[] pubkeyBytes = connectedScript.getPubKey();
			return keyBag.findKeyFromPubKey(pubkeyBytes);
		} else if (connectedScript.isSentToMultiSig()) {
			return getConnectedKey(keyBag, connectedScript.getPubKeys());
		} else {
			throw new ScriptException("Could not understand form of connected output script: " + connectedScript);
		}
	}

	public ECKey getConnectedKey(KeyBag keyBag, List<ECKey> ecs) throws ScriptException {

		for (ECKey ec : ecs) {
			ECKey a = keyBag.findKeyFromPubKey(ec.getPubKey());
			if (a != null)
				return a;
		}
		throw new ScriptException("Could not understand form of connected output script: " + ecs);
	}

	/**
	 * Returns the RedeemData identified in the connected output, for either
	 * pay-to-address scripts, pay-to-key or P2SH scripts. If the script forms
	 * cannot be understood, throws ScriptException.
	 *
	 * @return a RedeemData or null if the connected data cannot be found in the
	 *         wallet.
	 */
	@Nullable
	public RedeemData getConnectedRedeemData(KeyBag keyBag) throws ScriptException {
		TransactionOutput connectedOutput = getConnectedOutput();
		checkNotNull(connectedOutput, "Input is not connected so cannot retrieve key");
		Script connectedScript = connectedOutput.getScriptPubKey();
		if (connectedScript.isSentToAddress()) {
			byte[] addressBytes = connectedScript.getPubKeyHash();
			return RedeemData.of(keyBag.findKeyFromPubHash(addressBytes), connectedScript);
		} else if (connectedScript.isSentToRawPubKey()) {
			byte[] pubkeyBytes = connectedScript.getPubKey();
			return RedeemData.of(keyBag.findKeyFromPubKey(pubkeyBytes), connectedScript);
		} else if (connectedScript.isPayToScriptHash()) {
			byte[] scriptHash = connectedScript.getPubKeyHash();
			return keyBag.findRedeemDataFromScriptHash(scriptHash);
		} else if (connectedScript.isSentToMultiSig()) {

			return RedeemData.of(getConnectedKey(keyBag, connectedScript.getPubKeys()), connectedScript);
		} else {
			throw new ScriptException("Could not understand form of connected output script: " + connectedScript);
		}
	}

	@Override
	public String toString() {
		return blockHash + " : " + txHash + " : " + index;
	}

	/**
	 * Returns the hash of the outpoint.
	 */
	@Override
	public Sha256Hash getHash() {
		return Sha256Hash.of(Utils.addAll(blockHash.getBytes(), txHash.getBytes()));
	}

	public Sha256Hash getTxHash() {
		return txHash;
	}

	public Sha256Hash getBlockHash() {
		return blockHash;
	}

	public long getIndex() {
		return index;
	}

	public void setIndex(long index) {
		this.index = index;
	}

	/**
	 * Coinbase transactions have special outPoints with hashes of zero. If this is
	 * such an outPoint, returns true.
	 */
	public boolean isCoinBase() {
		return getBlockHash().equals(Sha256Hash.ZERO_HASH) && getTxHash().equals(Sha256Hash.ZERO_HASH)
				&& (getIndex() & 0xFFFFFFFFL) == 0xFFFFFFFFL;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		TransactionOutPoint other = (TransactionOutPoint) o;
		return getIndex() == other.getIndex() && getHash().equals(other.getHash());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(getIndex(), getHash());
	}
}
