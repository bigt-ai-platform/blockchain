/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;

import net.bigtangle.core.ContractEventInfo;

public class GetContractEventInfoResponse extends AbstractResponse {


	private List<ContractEventInfo> outputs;


    public List<ContractEventInfo> getOutputs() {
		return outputs;
	}

	public void setOutputs(List<ContractEventInfo> outputs) {
		this.outputs = outputs;
	}

}
