package dev.beacon.incident.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The S3 client itself comes from Spring Cloud AWS ({@code spring.cloud.aws.s3.*}) and talks to S3
 * from inside the network. The presigner is defined here instead, because the URLs it signs are used
 * by browsers, which may need a different endpoint (see {@link StorageProperties#publicEndpoint()}).
 * Defining it replaces Spring Cloud AWS's own presigner.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfig {

    @Bean
    S3Presigner s3Presigner(StorageProperties properties, AwsCredentialsProvider credentials, AwsRegionProvider region) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .credentialsProvider(credentials)
                .region(region.getRegion())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        .build());
        if (properties.publicEndpoint() != null) {
            builder.endpointOverride(properties.publicEndpoint());
        }
        return builder.build();
    }
}
