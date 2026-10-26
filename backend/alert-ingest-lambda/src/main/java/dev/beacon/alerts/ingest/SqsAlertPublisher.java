package dev.beacon.alerts.ingest;

import software.amazon.awssdk.services.sqs.SqsClient;

import dev.beacon.alerts.AlertMessage;
import dev.beacon.alerts.AlertMessageCodec;

public class SqsAlertPublisher implements AlertPublisher {

    private final SqsClient sqs;
    private final String queueUrl;

    public SqsAlertPublisher(SqsClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public void publish(AlertMessage message) {
        sqs.sendMessage(request -> request.queueUrl(queueUrl).messageBody(AlertMessageCodec.toJson(message)));
    }
}
