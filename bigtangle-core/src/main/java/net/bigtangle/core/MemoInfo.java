/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.utils.Json;

/*
 * help to set memo string as key value list
 */
public class MemoInfo implements java.io.Serializable {
    public static final String MEMO = "memo";
    public static final String ENCRYPT = "SignedData";
    private static final long serialVersionUID = 6992138619113601243L;

    private List<KeyValue> kv;

    public MemoInfo() {
    }

    /*
     *  add string memo 
     */
    public MemoInfo(String memo) {
        kv = new ArrayList<>();
        KeyValue keyValue = new KeyValue();
        keyValue.setKey(MEMO);
        keyValue.setValue(memo);
        kv.add(keyValue);
    }

    /*
     * add ENCRYPT data as key value
     */
    public void addEncryptMemo(String memo) {
        if (kv == null) {
            kv = new ArrayList<>();
        }

        KeyValue keyValue = new KeyValue();
        keyValue.setKey(ENCRYPT);
        keyValue.setValue(memo);
        kv.add(keyValue);

    }

    public String toJson() throws JsonProcessingException {
        return Json.jsonmapper().writeValueAsString(this);

    }

    public static MemoInfo parse(String jsonStr) throws IOException {
        if (jsonStr == null)
            return null;
        return Json.jsonmapper().readValue(jsonStr, MemoInfo.class);
    }

    /*
     * used for display the memo and cutoff maximal to 20 chars
     */
    public static String parseToString(String jsonStr) {
        try {
            if (jsonStr == null)
                return null;
            MemoInfo m = Json.jsonmapper().readValue(jsonStr, MemoInfo.class);
            StringBuilder s = new StringBuilder();
            for (KeyValue keyvalue : m.getKv()) {
                if (valueDisplay(keyvalue) != null && keyvalue.getKey() != null && !keyvalue.getKey().equals("null")
                        && !keyvalue.getKey().isEmpty()) {
                    s.append(keyvalue.getKey()).append(": ").append(valueDisplay(keyvalue)).append(" \n");
                }
            }
            return s.toString();
        } catch (Exception e) {
            return jsonStr;
        }
    }

    private static String valueDisplay(KeyValue keyvalue) {
        if (keyvalue.getValue() == null)
            return "";
        if (keyvalue.getValue().length() < 40) {
            return keyvalue.getValue();
        } else {
            return keyvalue.getValue().substring(0, 40) + "...";
        }
    }

    public List<KeyValue> getKv() {
        return kv;
    }

    public void setKv(List<KeyValue> kv) {
        this.kv = kv;
    }

}
