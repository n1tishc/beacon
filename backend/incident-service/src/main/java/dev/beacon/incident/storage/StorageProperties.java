package dev.beacon.incident.storage;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where attachments are stored, and for how long the URLs handed to browsers stay valid.
 *
 * @param publicEndpoint the S3 endpoint as the browser sees it. Unset in AWS. Locally the service
 *                       reaches LocalStack as {@code localstack:4566} but the browser needs
 *                       {@code localhost:4566}, and the host is part of a presigned URL's signature,
 *                       so the URL has to be signed for the browser's host from the start.
 * @param pathStyleAccess {@code http://host/bucket/key} instead of {@code http://bucket.host/key};
 *                        LocalStack needs it, AWS does not.
 */
@Validated
@ConfigurationProperties("beacon.attachments")
public record StorageProperties(
        @NotBlank String bucket,
        URI publicEndpoint,
        boolean pathStyleAccess,
        @NotNull Duration uploadUrlTtl,
        @NotNull Duration downloadUrlTtl) {
}
