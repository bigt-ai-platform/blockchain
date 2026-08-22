/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.utils;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class Json {

	private static final ObjectMapper MAPPER;

	static {
		// Batch-block sync responses serialize thousands of transactions as
		// hex inside one JSON string — routinely >20 MB. Jackson 2.16+
		// defaults reject strings beyond 20 MB (StreamConstraintsException),
		// which silently killed the blocksFromNonChainHeight bulk transfer
		// sync and permanently stalled lagging nodes. Lift the cap.
		JsonFactory factory = JsonFactory.builder()
				.streamReadConstraints(StreamReadConstraints.builder()
						.maxStringLength(200_000_000)
						.build())
				.build();
		ObjectMapper mapper = new ObjectMapper(factory);
		mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
		mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		mapper.setSerializationInclusion(Include.NON_EMPTY);
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		MAPPER = mapper;
	}

	public static ObjectMapper jsonmapper() {
		return MAPPER;
	}
}
