/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.kafka;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.server.service.BlockService;

@Service
public class BlockStreamHandler extends AbstractStreamHandler {

    @Autowired
    BlockService blockService;

    @Override
    public void run(StreamsBuilder streamBuilder) {

        dorun(streamBuilder);

    }

    public void dorun(StreamsBuilder streamBuilder) {
        
            final KStream<byte[], byte[]> input = streamBuilder.stream(kafkaConfiguration.getTopicOutName());

            input.map((key, bytes) -> KeyValue.pair(key,
                    blockService.addConnectedFromKafka(key, bytes )));
        
    }

}
