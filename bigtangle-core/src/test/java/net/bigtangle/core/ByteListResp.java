package net.bigtangle.core;

import java.util.ArrayList;
import java.util.List;

import net.bigtangle.core.response.AbstractResponse;

public class ByteListResp extends AbstractResponse {

    private List<ByteResp> list = new ArrayList<ByteResp>();

    public List<ByteResp> getList() {
        return list;
    }

    public void setList(List<ByteResp> list) {
        this.list = list;
    }
}
