/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2012 Matt Corallo.
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

import java.beans.Transient;
import java.io.Serializable;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Objects;

import net.bigtangle.script.Script;

/**
 * A UTXO message contains the information necessary to check a spending
 * transaction.
 */
public class UTXO extends SpentBlock implements  Serializable {

    private static final long serialVersionUID = 1L;
	private Coin value;
    @JsonIgnore
    private Script script;
    private Sha256Hash hash;
    private long index;
    private boolean coinbase;
    private String address;
    private String fromaddress;

    private boolean spendPending;
    private long spendPendingTime;
    private String tokenId;

    private long minimumsign;
    // saved in database as JSON from MemoInfo,
    // but it is simple kv text from database to display in UI
    private String memo;

    /** Chainlength of the containing block (for finality status display). */
    private long chainlength;
   
    // JSON
    public UTXO() {
    }

    public long getChainlength() {
        return chainlength;
    }

    public void setChainlength(long chainlength) {
        this.chainlength = chainlength;
    }

    public String keyAsString() {
        return getBlockHashHex() + "-" + Utils.HEX.encode(this.hash.getBytes()) + "-" + index;
    }

    public long getSpendPendingTime() {
        return spendPendingTime;
    }

    public boolean isZero() {
        return value.isZero();
    }

    public void setSpendPendingTime(long spendPendingTime) {
        this.spendPendingTime = spendPendingTime;
    }

    public void setScriptHex(String scriptHex) {
        this.script = new Script(Utils.HEX.decode(scriptHex));
    }

    public void setHashHex(String hashHex) {
        this.hash = Sha256Hash.wrap(hashHex);
    }

    public void setValue(Coin value) {
        this.value = value;
    }

    public void setHash(Sha256Hash hash) {
        this.hash = hash;
    }

    public void setIndex(long index) {
        this.index = index;
    }

    public void setCoinbase(boolean coinbase) {
        this.coinbase = coinbase;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public boolean isMultiSig() {
        return minimumsign > 1l;
    }

    public String getTokenId() {
        return tokenId;
    }

  
    public void setTokenid(String tokenid) {
        this.tokenId = tokenid;
    }

    @Transient
    public byte[] getTokenidBuf() {
        return Utils.HEX.decode(this.tokenId);
    }

    public String getFromaddress() {
        return fromaddress;
    }

    public void setFromaddress(String fromaddress) {
        this.fromaddress = fromaddress;
    }

    /**
     * Creates a stored transaction output.
     *
     * @param hash
     *            The hash of the containing transaction.
     * @param index
     *            The outpoint.
     * @param value
     *            The value available.
     * @param height
     *            The height this output was created in.
     * @param coinbase
     *            The coinbase flag.
     * @param address
     *            The address.
     */
    public UTXO(Sha256Hash hash, long index, Coin value, boolean coinbase, Script script, String address,
            Sha256Hash blockhash, String fromaddress, String memo, String tokenid, boolean spent, boolean confirmed,
            boolean spendPending, long minimumsign, long spendPendingTime, long time,  Sha256Hash spenderBlockHash) {
        this.hash = hash;
        this.index = index;
        this.value = value;
        this.script = script;
        this.coinbase = coinbase;
        this.setBlockHash(blockhash);
        this.fromaddress = fromaddress;
  
        this.memo =memo;
                //;

        this.address = address;
        this.setSpent(spent);
        this.  setSpenderBlockHash(spenderBlockHash);
        this.tokenId = tokenid;
        this.setConfirmed(confirmed);
        this.spendPending = spendPending;
        this.minimumsign = minimumsign;
        this.spendPendingTime = spendPendingTime;
        this.setTime(time);
    }

    public String  memoToString() {
        return MemoInfo.parseToString(memo);
    }

    
    /** The value which this Transaction output holds. */
    public Coin getValue() {
        return value;
    }

    /**
     * The Script object which you can use to get address, script bytes or
     * script type.
     */
    @Transient
    public Script getScript() {
        return script;
    }

    public void setScript(Script script) {
        this.script = script;
    }

    public String getScriptHex() {
        return Utils.HEX.encode(this.script.getProgram());
    }

    /** The hash of the transaction which holds this output. */
    @Transient
    public Sha256Hash getTxHash() {
        return hash;
    }

    public String getHashHex() {
        return Utils.HEX.encode(hash.getBytes());
    }

    /** The index of this output in the transaction which holds it. */
    public long getIndex() {
        return index;
    }

    /** Gets the flag of whether this was created by a coinbase tx. */
    public boolean isCoinbase() {
        return coinbase;
    }

    /**
     * The address of this output, can be the empty string if none was provided
     * at construction time or was deserialized
     */
    public String getAddress() {
        return address;
    }

    public String toStringShort() {
        return String.format(Locale.US, "UTXO %s (%s:%d)", value.toString(), hash, index);
    }

    @Override
    public String toString() {
        return "UTXO [value=" + value + ", \n script=" + script + ", \n hash=" + hash + ", \n index=" + index
                + ", coinbase=" + coinbase + ", \n address=" + address + ", \n fromaddress=" + fromaddress
                + ", \n time=" + Utils.dateTimeFormat(getTime()*1000)
                + ", \n memo=" + memo 
                + ", \n confirmed=" + isConfirmed() 
                + ", \n spent=" + isSpent() 
                + ", \n spendPending=" + spendPending 
                + ", \n spendPendingTime="          + spendPendingTime 
                + ", \n spenderBlockHash="          + getSpenderBlockHash()  
                + ", \n tokenId=" + tokenId + ", \n minimumsign=" + minimumsign + " \n ]";
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getIndex(), getTxHash(),getBlockHash());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        UTXO other = (UTXO) o;
        return getIndex() == other.getIndex() && getTxHash().equals(other.getTxHash())
        		 && getBlockHash().equals(other.getBlockHash()) ;
    }

    public String getMemo() {

        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public boolean isSpendPending() {
        return spendPending;
    }

    public void setSpendPending(boolean spendPending) {
        this.spendPending = spendPending;
    }

    public long getMinimumsign() {
        return minimumsign;
    }

    public void setMinimumsign(long minimumsign) {
        this.minimumsign = minimumsign;
    }

   
}
