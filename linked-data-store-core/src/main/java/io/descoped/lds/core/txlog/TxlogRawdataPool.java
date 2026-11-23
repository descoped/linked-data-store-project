package io.descoped.lds.core.txlog;

import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataProducer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Optional.ofNullable;

public class TxlogRawdataPool {

    private final RawdataClient client;
    private final boolean splitSources;
    private final String defaultSource;
    private final String txLogTopicPrefix;
    private final boolean keepsMessages;

    private final Map<String, RawdataProducer> producerByTopic = new ConcurrentHashMap<>();

    /**
     * @param keepsMessages false when the provider of the client keeps no message ({@code discard}), so that the
     *                      last message of a topic is not known
     */
    public TxlogRawdataPool(RawdataClient client, boolean splitSources, String defaultSource, String txLogTopicPrefix,
                            boolean keepsMessages) {
        this.client = client;
        this.splitSources = splitSources;
        this.defaultSource = defaultSource;
        this.txLogTopicPrefix = txLogTopicPrefix;
        this.keepsMessages = keepsMessages;
    }

    /**
     * @return whether the transaction log keeps what is written to it; the {@code discard} provider keeps nothing
     */
    public boolean keepsMessages() {
        return keepsMessages;
    }

    public RawdataClient getClient() {
        return client;
    }

    public boolean isSplitSources() {
        return splitSources;
    }

    public String getDefaultSource() {
        return defaultSource;
    }

    public String getTxLogTopicPrefix() {
        return txLogTopicPrefix;
    }

    public RawdataProducer producer(String source) {
        String topic = topicOf(source);
        return producerByTopic.computeIfAbsent(topic, client::producer);
    }

    public String topicOf(String source) {
        if (splitSources) {
            // Use separate tx-log topic per source
            return txLogTopicPrefix + ofNullable(source).orElse(defaultSource);
        }
        // Always use default source
        return txLogTopicPrefix + defaultSource;
    }

    public RawdataMessage getLastMessage(String source) {
        if (!splitSources && !source.equals(defaultSource)) {
            return null;
        }
        String topic = topicOf(source);
        return client.lastMessage(topic);
    }
}
