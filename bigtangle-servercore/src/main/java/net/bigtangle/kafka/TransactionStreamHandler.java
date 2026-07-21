package net.bigtangle.kafka;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Transaction;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.service.MempoolService;

@Service
public class TransactionStreamHandler extends AbstractStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(TransactionStreamHandler.class);

    @Autowired
    MempoolService mempoolService;

    @Autowired
    NetworkParameters networkParameters;

    @Override
    public void run(StreamsBuilder streamBuilder) {
        dorun(streamBuilder);
    }

    public void dorun(StreamsBuilder streamBuilder) {
        final KStream<byte[], byte[]> input = streamBuilder.stream(kafkaConfiguration.getTopicOutName());
        input.map((key, bytes) -> {
            try {
                byte[] decompressed = bytes;
                Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(decompressed);
                mempoolService.submitTransaction(tx);
                log.debug("Transaction from kafka added to mempool");
            } catch (Exception e) {
                log.debug("Failed to process kafka transaction", e);
            }
            return KeyValue.pair(key, bytes);
        });
    }
}
