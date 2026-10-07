package com.intelhub.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

import java.util.UUID;

@Component
public class DocumentIngestionProducer {
    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;

    public DocumentIngestionProducer(RocketMQTemplate rocketMQTemplate,
                                     @Value("${intelhub.ingestion.topic}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    public void enqueue(UUID documentId) {
        rocketMQTemplate.syncSend(topic, documentId.toString());
    }
}
