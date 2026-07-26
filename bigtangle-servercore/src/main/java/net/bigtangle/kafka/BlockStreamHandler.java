package net.bigtangle.kafka;

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
    protected String topic() {
        return kafkaConfiguration.getBlockTopic();
    }

    @Override
    protected void process(KStream<String, byte[]> stream) {
        stream.foreach((key, bytes) ->
            blockService.addConnectedFromKafka(key.getBytes(), bytes));
    }
}
