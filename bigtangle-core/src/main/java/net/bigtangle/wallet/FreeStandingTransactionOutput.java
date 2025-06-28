/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.wallet;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.params.NetworkParameters;

public class FreeStandingTransactionOutput extends TransactionOutput {
    
    private UTXO output;
    
    /**
     * Construct a free standing Transaction Output.
     * 
     * @param params
     *            The network parameters.
     * @param output
     *            The stored output (free standing).
     */
    public FreeStandingTransactionOutput(NetworkParameters params, UTXO output ) {
        super(params, null, output.getValue(), output.getScript().getProgram());
        this.output = output;
       
    }

    /**
     * Get the {@link UTXO}.
     * 
     * @return The stored output.
     */
    public UTXO getUTXO() {
        return output;
    }
 
    @Override
    public int getIndex() {
        return (int) output.getIndex();
    }

    @Override
    public Sha256Hash getParentTransactionHash() {
        return output.getTxHash();
    }
}
