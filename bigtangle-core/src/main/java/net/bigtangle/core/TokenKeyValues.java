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

public class TokenKeyValues implements java.io.Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private List<KeyValue> keyvalues;

	public void addKeyvalue(KeyValue kv) {
		if (keyvalues == null) {
			keyvalues = new ArrayList<>();
			keyvalues.add(kv);
		}else {
		    keyvalues.add(kv);
		}
	}

	public byte[] toByteArray() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			List<KeyValue> list = keyvalues;
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
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static TokenKeyValues parse(byte[] buf) throws IOException {
		TokenKeyValues tkv = new TokenKeyValues();
		if (buf == null || buf.length == 0) return tkv;
		DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf));
		int size = dis.readInt();
		for (int i = 0; i < size; i++) {
			int len = dis.readInt();
			byte[] bytes = new byte[len];
			dis.readFully(bytes);
			tkv.addKeyvalue(new KeyValue().parse(bytes));
		}
		dis.close();
		return tkv;
	}

	public List<KeyValue> getKeyvalues() {
		return keyvalues;
	}

}
