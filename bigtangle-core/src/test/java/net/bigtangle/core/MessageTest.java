/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import net.bigtangle.exception.ProtocolException;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;

public class MessageTest {

	// If readStr() is vulnerable this causes OutOfMemory
	@Test
	public void readStrOfExtremeLength() throws Exception {
		assertThrows(ProtocolException.class, () -> {
			NetworkParameters params = MainNetParams.get();
			VarInt length = new VarInt(Integer.MAX_VALUE);
			byte[] payload = length.encode();
			  VarStrMessage.from (params, payload);
		});

	}

	static class VarStrMessage extends Message {
		public VarStrMessage(NetworkParameters params ) {
			super(params );
		}
		public static  VarStrMessage from(NetworkParameters params, byte[] payload) {
			VarStrMessage message = new VarStrMessage(params);
			message.setValues3(params, payload, 0);
			return message;
		}
		@Override
		protected void parse() throws ProtocolException {
			readStr();
		}
	}

	// If readBytes() is vulnerable this causes OutOfMemory
	@Test
	public void readByteArrayOfExtremeLength() throws Exception {
		assertThrows(ProtocolException.class, () -> {
			NetworkParameters params = MainNetParams.get();
			VarInt length = new VarInt(Integer.MAX_VALUE);
			byte[] payload = length.encode();
			  VarBytesMessage. from(params, payload);
		});

	}

	static class VarBytesMessage extends Message {
		public VarBytesMessage(NetworkParameters params ) {
			super(params );
		}
		public static VarBytesMessage from(NetworkParameters params, byte[] payload) {
			VarBytesMessage message = new VarBytesMessage(params);
			message.setValues3(params, payload, 0);
			return message;
		}
		@Override
		protected void parse() throws ProtocolException {
			readByteArray();
		}
	}
}
