/*******************************************************************************
 *  Copyright   2026  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.response;

import net.bigtangle.core.UTXO;

/**
 * Detail of a single UTXO plus the block that contains it (for the balance
 * screen's "… for more" action). Fetched on demand — never joined into the
 * bulk outputs/history queries.
 */
public class OutputDetailResponse extends AbstractResponse {

    private UTXO output;
    private long blockHeight;
    private long blockChainlength;
    private boolean blockConfirmed;
    private long blockTime;
    private String blockHash;

    public static OutputDetailResponse create(UTXO output, long blockHeight, long blockChainlength,
            boolean blockConfirmed, long blockTime, String blockHash) {
        OutputDetailResponse res = new OutputDetailResponse();
        res.output = output;
        res.blockHeight = blockHeight;
        res.blockChainlength = blockChainlength;
        res.blockConfirmed = blockConfirmed;
        res.blockTime = blockTime;
        res.blockHash = blockHash;
        return res;
    }

    public UTXO getOutput() {
        return output;
    }

    public long getBlockHeight() {
        return blockHeight;
    }

    public long getBlockChainlength() {
        return blockChainlength;
    }

    public boolean isBlockConfirmed() {
        return blockConfirmed;
    }

    public long getBlockTime() {
        return blockTime;
    }

    public String getBlockHash() {
        return blockHash;
    }
}
