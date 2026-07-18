/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    public byte[] toByteArray() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream dos = new DataOutputStream(baos);
            List<KeyValue> list = kv;
            if (list == null) {
                dos.writeInt(0);
            } else {
                dos.writeInt(list.size());
                for (KeyValue kv : list) {
                    byte[] bytes = kv.toByteArray();
                    dos.writeInt(bytes.length);
                    dos.write(bytes);
                }
            }
            dos.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    public static MemoInfo parse(byte[] buf) throws IOException {
        if (buf == null || buf.length == 0) return null;
        MemoInfo m = new MemoInfo();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf));
        int size = dis.readInt();
        m.kv = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            int len = dis.readInt();
            byte[] bytes = new byte[len];
            dis.readFully(bytes);
            m.kv.add(new KeyValue().parse(bytes));
        }
        dis.close();
        return m;
    }

    /** Parse from a String — tries hex-encoded binary first, then legacy JSON. */
    public static MemoInfo parse(String str) throws IOException {
        if (str == null) return null;
        // Try hex-encoded binary first
        try {
            byte[] buf = Utils.HEX.decode(str);
            return parse(buf);
        } catch (Exception e) {
            // Fallback to legacy JSON
            return fromJson(str);
        }
    }

    /*
     * used for display the memo and cutoff maximal to 20 chars
     */
    public static String parseToString(String str) {
        try {
            if (str == null) return null;
            byte[] buf = Utils.HEX.decode(str);
            MemoInfo m = parse(buf);
            if (m == null) return null;
            StringBuilder s = new StringBuilder();
            for (KeyValue keyvalue : m.getKv()) {
                if (valueDisplay(keyvalue) != null && keyvalue.getKey() != null && !keyvalue.getKey().equals("null")
                        && !keyvalue.getKey().isEmpty()) {
                    s.append(keyvalue.getKey()).append(": ").append(valueDisplay(keyvalue)).append(" \n");
                }
            }
            return s.toString();
        } catch (Exception e) {
            // Fallback: try parsing as legacy JSON
            try {
                return parseToStringJson(str);
            } catch (Exception e2) {
                return str;
            }
        }
    }

    private static String parseToStringJson(String jsonStr) {
        if (jsonStr == null) return null;
        try {
            MemoInfo m = fromJson(jsonStr);
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

    // Legacy JSON serialization — kept for backward compatibility with existing blocks
    public String toJson() {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT, false);
            mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static MemoInfo fromJson(String jsonStr) throws IOException {
        if (jsonStr == null) return null;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(jsonStr, MemoInfo.class);
    }
}
