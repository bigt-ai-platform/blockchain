/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;

import net.bigtangle.core.OutputsMulti;
import net.bigtangle.core.UTXO;

public class OutputsDetailsResponse extends AbstractResponse {

    private UTXO outputs;
    private List<OutputsMulti> outputsMultis;

    public static AbstractResponse create(UTXO outputs) {
        OutputsDetailsResponse res = new OutputsDetailsResponse();
        res.outputs = outputs;
        return res;
    }

    public static AbstractResponse create(List<OutputsMulti> outputsMultis) {
        OutputsDetailsResponse res = new OutputsDetailsResponse();
        res.outputsMultis = outputsMultis;
        return res;
    }

    public UTXO getOutputs() {
        return outputs;
    }

    public List<OutputsMulti> getOutputsMultis() {
        return outputsMultis;
    }
}