/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;

public class GetTransactionListResponse extends AbstractResponse {
    private List<byte[]> transactionlist;

    public static GetTransactionListResponse create(List<byte[]> transactionlist) {
        GetTransactionListResponse res = new GetTransactionListResponse();
        res.transactionlist = transactionlist;
        return res;
    }

    public List<byte[]> getTransactionlist() {
        return transactionlist;
    }

    public void setTransactionlist(List<byte[]> transactionlist) {
        this.transactionlist = transactionlist;
    }

}
