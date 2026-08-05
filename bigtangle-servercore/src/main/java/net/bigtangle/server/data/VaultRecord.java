package net.bigtangle.server.data;

import java.util.Objects;

import net.bigtangle.core.Sha256Hash;

public class VaultRecord {

    private String chainId;
    private Sha256Hash utxoBlockHash;
    private long utxoIndex;
    /** The block whose CROSSTANGLE tx created the actual vault output (spent on peg-out). */
    private Sha256Hash pegInBlockHash;
    private long amount;
    private String tokenIdHex;
    private String ownerAddress;
    private boolean spent;

    public VaultRecord() {
    }

    public VaultRecord(String chainId, Sha256Hash utxoBlockHash, long utxoIndex, long amount,
            String tokenIdHex, String ownerAddress, boolean spent) {
        this(chainId, utxoBlockHash, utxoIndex, null, amount, tokenIdHex, ownerAddress, spent);
    }

    public VaultRecord(String chainId, Sha256Hash utxoBlockHash, long utxoIndex, Sha256Hash pegInBlockHash,
            long amount, String tokenIdHex, String ownerAddress, boolean spent) {
        this.chainId = chainId;
        this.utxoBlockHash = utxoBlockHash;
        this.utxoIndex = utxoIndex;
        this.pegInBlockHash = pegInBlockHash;
        this.amount = amount;
        this.tokenIdHex = tokenIdHex;
        this.ownerAddress = ownerAddress;
        this.spent = spent;
    }

    public String getChainId() { return chainId; }
    public void setChainId(String chainId) { this.chainId = chainId; }

    public Sha256Hash getUtxoBlockHash() { return utxoBlockHash; }
    public void setUtxoBlockHash(Sha256Hash utxoBlockHash) { this.utxoBlockHash = utxoBlockHash; }

    public long getUtxoIndex() { return utxoIndex; }
    public void setUtxoIndex(long utxoIndex) { this.utxoIndex = utxoIndex; }

    public Sha256Hash getPegInBlockHash() { return pegInBlockHash; }
    public void setPegInBlockHash(Sha256Hash pegInBlockHash) { this.pegInBlockHash = pegInBlockHash; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getTokenIdHex() { return tokenIdHex; }
    public void setTokenIdHex(String tokenIdHex) { this.tokenIdHex = tokenIdHex; }

    public String getOwnerAddress() { return ownerAddress; }
    public void setOwnerAddress(String ownerAddress) { this.ownerAddress = ownerAddress; }

    public boolean isSpent() { return spent; }
    public void setSpent(boolean spent) { this.spent = spent; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VaultRecord)) return false;
        VaultRecord that = (VaultRecord) o;
        return utxoIndex == that.utxoIndex && amount == that.amount && spent == that.spent
                && Objects.equals(chainId, that.chainId)
                && Objects.equals(utxoBlockHash, that.utxoBlockHash)
                && Objects.equals(pegInBlockHash, that.pegInBlockHash)
                && Objects.equals(tokenIdHex, that.tokenIdHex)
                && Objects.equals(ownerAddress, that.ownerAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chainId, utxoBlockHash, utxoIndex, pegInBlockHash, amount, tokenIdHex, ownerAddress, spent);
    }
}
