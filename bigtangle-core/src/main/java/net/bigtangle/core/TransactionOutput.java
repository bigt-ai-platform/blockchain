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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;

import net.bigtangle.exception.ProtocolException;
import net.bigtangle.exception.ScriptException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.wallet.Wallet;

/**
 * <p>
 * A TransactionOutput message contains a scriptPubKey that controls who is able
 * to spend its value. It is a sub-part of the Transaction message.
 * </p>
 * 
 * <p>
 * Instances of this class are not safe for use by multiple threads.
 * </p>
 */
public class TransactionOutput extends ChildMessage {
    private static final Logger log = LoggerFactory.getLogger(TransactionOutput.class);

    // The output's value is kept as a native type in order to save class
    // instances.
    private Coin value;

    // A transaction output has a script used for authenticating that the
    // redeemer is allowed to spend
    // this output.
    private byte[] scriptBytes;

    // The script bytes are parsed and turned into a Script on demand.
    private Script scriptPubKey;

    // These fields are not Bitcoin serialized. They are used for tracking
    // purposes in our wallet
    // only. If set to true, this output is counted towards our balance. If
    // false and spentBy is null the tx output
    // was owned by us and was sent to somebody else. If false and spentBy is
    // set it means this output was owned by
    // us and used in one of our own transactions (eg, because it is a change
    // output).
    private boolean availableForSpending;
    @Nullable
    private TransactionInput spentBy;

    private int scriptLen;
    private int tokenLen;

    private String description;

 
    /**
     * Deserializes a transaction output message. This is usually part of a
     * transaction message.
     *
     * @param params
     *            NetworkParameters object.
     * @param payload
     *            Bitcoin protocol formatted byte array containing message
     *            content.
     * @param offset
     *            The location of the first payload byte within the array.
     * @param serializer
     *            the serializer to use for this message.
     * @throws ProtocolException
     */
    public TransactionOutput(NetworkParameters params, @Nullable Transaction parent, byte[] payload, int offset,
            MessageSerializer serializer) throws ProtocolException {
        super(params, payload, offset, parent, serializer, UNKNOWN_LENGTH);
        availableForSpending = true;
    }

    /**
     * Creates an output that sends 'value' to the given address (public key
     * hash). The amount should be created with something like
     * {@link Coin#valueOf(int, int)}. Typically you would use
     * {@link Transaction#addOutput(Coin, Address)} instead of creating a
     * TransactionOutput directly.
     */
    public  static TransactionOutput fromAddress(NetworkParameters params, @Nullable Transaction parent, Coin value, Address to) {
    	return new TransactionOutput(params, parent, value, ScriptBuilder.createOutputScript(to).getProgram());
    }

    /**
     * Creates an output that sends 'value' to the given public key using a
     * simple CHECKSIG script (no addresses). The amount should be created with
     * something like {@link Coin#valueOf(int, int)}. Typically you would use
     * {@link Transaction#addOutput(Coin, ECKey)} instead of creating an output
     * directly.
     */
    public  static TransactionOutput fromCoinKey(NetworkParameters params, @Nullable Transaction parent, Coin value, ECKey to) {
    	return new TransactionOutput(params, parent, value, ScriptBuilder.createOutputScript(to).getProgram());
    }

    public TransactionOutput(NetworkParameters params, @Nullable Transaction parent, Coin value, byte[] scriptBytes) {
        super(params);
        // Negative values obviously make no sense, except for -1 which is used
        // as a sentinel value when calculating
        // SIGHASH_SINGLE signatures, so unfortunately we have to allow that
        // here.
        checkArgument(value.signum() >= 0 || value.equals(Coin.NEGATIVE_SATOSHI), "Negative values not allowed");
        // checkArgument(!params.hasMaxMoney() ||
        // value.compareTo(params.getMaxMoney()) <= 0, "Values larger than
        // MAX_MONEY not allowed");
        this.value = value;
        this.scriptBytes = scriptBytes;
        setParent(parent);
        availableForSpending = true;
        length =  this.value.getTokenid().length + VarInt.sizeOf(this.value.getTokenid().length)
                + VarInt.sizeOf(scriptBytes.length) + scriptBytes.length
                +VarInt.sizeOf(this.value.getValue().toByteArray().length) + this.value.getValue().toByteArray().length;
    }

    public Script getScriptPubKey() throws ScriptException {
        if (scriptPubKey == null) {
            scriptPubKey = new Script(scriptBytes);
        }
        return scriptPubKey;
    }

    /**
     * <p>
     * If the output script pays to an address as in
     * <a href="https://bitcoin.org/en/developer-guide#term-p2pkh"> P2PKH</a>,
     * return the address of the receiver, i.e., a base58 encoded hash of the
     * public key in the script.
     * </p>
     *
     * @param networkParameters
     *            needed to specify an address
     * @return null, if the output script is not the form <i>OP_DUP OP_HASH160
     *         <PubkeyHash> OP_EQUALVERIFY OP_CHECKSIG</i>, i.e., not P2PKH
     * @return an address made out of the public key hash
     */
    @Nullable
    public Address getAddressFromP2PKHScript(NetworkParameters networkParameters) throws ScriptException {
        if (getScriptPubKey().isSentToAddress())
            return getScriptPubKey().getToAddress(networkParameters);

        return null;
    }

    /**
     * <p>
     * If the output script pays to a redeem script, return the address of the
     * redeem script as described by, i.e., a base58 encoding of [one-byte
     * version][20-byte hash][4-byte checksum], where the 20-byte hash refers to
     * the redeem script.
     * </p>
     *
     * <p>
     * P2SH is described by <a href=
     * "https://github.com/bitcoin/bips/blob/master/bip-0016.mediawiki">BIP
     * 16</a> and
     * <a href="https://bitcoin.org/en/developer-guide#p2sh-scripts">documented
     * in the Bitcoin Developer Guide</a>.
     * </p>
     *
     * @param networkParameters
     *            needed to specify an address
     * @return null if the output script does not pay to a script hash
     * @return an address that belongs to the redeem script
     */
    @Nullable
    public Address getAddressFromP2SH(NetworkParameters networkParameters) throws ScriptException {
        if (getScriptPubKey().isPayToScriptHash())
            return getScriptPubKey().getToAddress(networkParameters);

        return null;
    }

    @Override
    protected void parse() throws ProtocolException {
            int vlen = (int) readVarInt();
            byte[] v = readBytes(vlen);
        tokenLen = (int) readVarInt();
        value = new Coin(new BigInteger(v), readBytes(tokenLen));
        
        scriptLen = (int) readVarInt();
        scriptBytes = readBytes(scriptLen);
        length = cursor - offset;

    }

    @Override
    protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {

        checkNotNull(scriptBytes);
        byte[] valuebytes = value.getValue().toByteArray();
         stream.write(new VarInt(valuebytes.length).encode()); 
         stream.write(valuebytes); 

        stream.write(new VarInt(value.getTokenid().length).encode());
        stream.write(value.getTokenid());
        stream.write(new VarInt(scriptBytes.length).encode());
        stream.write(scriptBytes);
    }

    /**
     * Returns the value of this output. This is the amount of currency that the
     * destination address receives.
     */
    public Coin getValue() {
        return value;

    }

    /**
     * Sets the value of this output.
     */
    public void setValue(Coin value) {
        checkNotNull(value);
        unCache();
        this.value = value;
    }

    /**
     * Gets the index of this output in the parent transaction, or throws if
     * this output is free standing. Iterates over the parents list to discover
     * this.
     */
    public int getIndex() {
        List<TransactionOutput> outputs = getParentTransaction().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i) == this)
                return i;
        }
        throw new IllegalStateException("Output linked to wrong parent transaction?");
    }

 
    /**
     * Sets this objects availableForSpending flag to false and the spentBy
     * pointer to the given input. If the input is null, it means this output
     * was signed over to somebody else rather than one of our own keys.
     * 
     * @throws IllegalStateException
     *             if the transaction was already marked as spent.
     */
    public void markAsSpent(TransactionInput input) {
        checkState(availableForSpending);
        availableForSpending = false;
        spentBy = input;
        if (parent != null)
            if (log.isDebugEnabled())
                log.debug("Marked {}:{} as spent by {}", getParentTransactionHash(), getIndex(), input);
            else if (log.isDebugEnabled())
                log.debug("Marked floating output as spent by {}", input);
    }

    /**
     * Resets the spent pointer / availableForSpending flag to null.
     */
    public void markAsUnspent() {
        if (parent != null)
            if (log.isDebugEnabled())
                log.debug("Un-marked {}:{} as spent by {}", getParentTransactionHash(), getIndex(), spentBy);
            else if (log.isDebugEnabled())
                log.debug("Un-marked floating output as spent by {}", spentBy);
        availableForSpending = true;
        spentBy = null;
    }

    /**
     * Returns whether {@link TransactionOutput#markAsSpent(TransactionInput)}
     * has been called on this class. A {@link Wallet} will mark a transaction
     * output as spent once it sees a transaction input that is connected to it.
     * Note that this flag can be false when an output has in fact been spent
     * according to the rest of the network if the spending transaction wasn't
     * downloaded yet, and it can be marked as spent when in reality the rest of
     * the network believes it to be unspent if the signature or script
     * connecting to it was not actually valid.
     */
    public boolean isAvailableForSpending() {
        return availableForSpending;
    }

    /**
     * The backing script bytes which can be turned into a Script object.
     * 
     * @return the scriptBytes
     */
    public byte[] getScriptBytes() {
        return scriptBytes;
    }

    /**
     * Returns true if this output is to a key in the wallet or to an
     * address/script we are watching.
     */
    public boolean isMineOrWatched(TransactionBag transactionBag) {
        return isMine(transactionBag) || isWatched(transactionBag);
    }

    /**
     * Returns true if this output is to a key, or an address we have the keys
     * for, in the wallet.
     */
    public boolean isWatched(TransactionBag transactionBag) {
        try {
            Script script = getScriptPubKey();
            return transactionBag.isWatchedScript(script);
        } catch (ScriptException e) {
            // Just means we didn't understand the output of this transaction:
            // ignore it.
            log.debug("Could not parse tx output script: {}", e.toString());
            return false;
        }
    }

    /**
     * Returns true if this output is to a key, or an address we have the keys
     * for, in the wallet.
     */
    public boolean isMine(TransactionBag transactionBag) {
        try {
            Script script = getScriptPubKey();
            if (script.isSentToRawPubKey()) {
                byte[] pubkey = script.getPubKey();
                return transactionBag.isPubKeyMine(pubkey);
            }
            if (script.isPayToScriptHash()) {
                return transactionBag.isPayToScriptHashMine(script.getPubKeyHash());
            } else {
                byte[] pubkeyHash = script.getPubKeyHash();
                return transactionBag.isPubKeyHashMine(pubkeyHash);
            }
        } catch (ScriptException e) {
            // Just means we didn't understand the output of this transaction:
            // ignore it.
            log.debug("Could not parse tx {} output script: {}", parent != null ? parent.getHash() : "(no parent)",
                    e.toString());
            return false;
        }
    }

    /**
     * Returns a human readable debug string.
     */
    @Override
    public String toString() {
        try {
            Script script = getScriptPubKey();
            StringBuilder buf = new StringBuilder("TxOut of ");
            buf.append(value.toString());
            if (script.isSentToAddress() || script.isPayToScriptHash())
                buf.append(" to ").append(script.getToAddress(params));
            else if (script.isSentToRawPubKey())
                buf.append(" to pubkey ").append(Utils.HEX.encode(script.getPubKey()));
            else if (script.isSentToMultiSig())
                buf.append(" to multisig");
            else
                buf.append(" (unknown type)");
            buf.append(" script:").append(script);
            return buf.toString();
        } catch (ScriptException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the connected input.
     */
    @Nullable
    public TransactionInput getSpentBy() {
        return spentBy;
    }

    /**
     * Returns the transaction that owns this output.
     */
    @Nullable
    public Transaction getParentTransaction() {
        return (Transaction) parent;
    }

    /**
     * Returns the transaction hash that owns this output.
     */
    @Nullable
    public Sha256Hash getParentTransactionHash() {
        return parent == null ? null : parent.getHash();
    }

    /**
     * Returns a new {@link TransactionOutPoint}, which is essentially a
     * structure pointing to this output. Requires that this output is not
     * detached.
     */
    public TransactionOutPoint getOutPointFor(Sha256Hash containingBlockHash) {
        return new TransactionOutPoint(params, getIndex(), containingBlockHash, getParentTransaction());
    }

    /**
     * Returns a copy of the output detached from its containing transaction, if
     * need be.
     */
    public TransactionOutput duplicateDetached() {
        return new TransactionOutput(params, null, value, org.spongycastle.util.Arrays.clone(scriptBytes));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        TransactionOutput other = (TransactionOutput) o;
        return value == other.value && (parent == null || (parent == other.parent && getIndex() == other.getIndex()))
                && Arrays.equals(scriptBytes, other.scriptBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value, parent, Arrays.hashCode(scriptBytes));
    }

//    public String getFromaddress() {
//        try {
//            Script script = getScriptPubKey(); 
//            if (script.isSentToAddress() || script.isPayToScriptHash())
//                return script.getToAddress(params).toString();
//            else if (script.isSentToRawPubKey())
//                ECKey.fromPublicOnly(script.getPubKey()).toAddress(params).toString();
//
//            return "";
//
//        } catch (ScriptException e) {
//            return "";
//        }
//    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

}
