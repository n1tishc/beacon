package dev.beacon.alerts.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import dev.beacon.alerts.AlertMessage;
import dev.beacon.alerts.AlertMessageCodec;
import dev.beacon.alerts.AlertSeverity;

/** The real SQS API (LocalStack) with the same HTTP client the Lambda uses. */
@Testcontainers
class SqsAlertPublisherIntegrationTest {

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse(System.getProperty("localstack.image", "localstack/localstack:4.14"))
                    .asCompatibleSubstituteFor("localstack/localstack"))
            .withServices(LocalStackContainer.Service.SQS);

    @Test
    void publishesTheMessageInTheContractFormat() {
        SqsClient sqs = SqsClient.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();
        String queueUrl = sqs.createQueue(request -> request.queueName("alerts")).queueUrl();
        AlertMessage message = new AlertMessage(UUID.randomUUID(), "prometheus", "fp-1", "Disk full", null,
                AlertSeverity.LOW, Instant.parse("2026-01-15T10:00:00Z"));

        new SqsAlertPublisher(sqs, queueUrl).publish(message);

        List<Message> received = sqs.receiveMessage(request -> request.queueUrl(queueUrl).waitTimeSeconds(5)).messages();
        assertThat(received).hasSize(1);
        assertThat(AlertMessageCodec.fromJson(received.getFirst().body())).isEqualTo(message);
    }
}
