/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

import java.util.List;

public class UserDataResponse extends AbstractResponse {

    private List<String> dataList;

    public static AbstractResponse createUserDataResponse(List<String> dataList) {
        UserDataResponse res = new UserDataResponse();
        res.dataList = dataList;
        return res;
    }

    public List<String> getDataList() {
        return dataList;
    }

    public void setDataList(List<String> dataList) {
        this.dataList = dataList;
    }
}
