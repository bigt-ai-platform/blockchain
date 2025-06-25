/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;

import net.bigtangle.core.BlockEvaluationDisplay;

public class GetBlockEvaluationsResponse extends AbstractResponse {
    private List<BlockEvaluationDisplay> evaluations;

    public static AbstractResponse create(List<BlockEvaluationDisplay> evaluations) {
        GetBlockEvaluationsResponse res = new GetBlockEvaluationsResponse();
        res.evaluations = evaluations;
        return res;
    }

    public List<BlockEvaluationDisplay> getEvaluations() {
        return evaluations;
    }

}
