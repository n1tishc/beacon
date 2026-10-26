package dev.beacon.incident.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import dev.beacon.incident.domain.Attachment;

/**
 * The only class that talks to S3. File bytes never pass through incident-service: the browser
 * uploads and downloads with short-lived presigned URLs, and this service only signs them and, once
 * an upload is reported done, checks what actually arrived.
 */
@Component
public class AttachmentStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public AttachmentStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    /**
     * A PUT URL for exactly this attachment: its key, its content type and its size are all part of
     * the signature, so the URL cannot be used to upload anything else or anything bigger.
     */
    public PresignedUpload presignUpload(Attachment attachment) {
        PresignedPutObjectRequest presigned = presigner.presignPutObject(request -> request
                .signatureDuration(properties.uploadUrlTtl())
                .putObjectRequest(put -> put
                        .bucket(properties.bucket())
                        .key(attachment.getObjectKey())
                        .contentType(attachment.getContentType())
                        .contentLength(attachment.getSizeBytes())));
        return new PresignedUpload(toUri(presigned.url()), browserHeaders(presigned.signedHeaders()),
                presigned.expiration());
    }

    /**
     * A GET URL that makes the browser download the file rather than display it: S3 answers with
     * {@code Content-Disposition: attachment}, so an uploaded file is never rendered as a page.
     */
    public PresignedDownload presignDownload(Attachment attachment) {
        PresignedGetObjectRequest presigned = presigner.presignGetObject(request -> request
                .signatureDuration(properties.downloadUrlTtl())
                .getObjectRequest(get -> get
                        .bucket(properties.bucket())
                        .key(attachment.getObjectKey())
                        .responseContentType(attachment.getContentType())
                        .responseContentDisposition(contentDisposition(attachment.getFileName()))));
        return new PresignedDownload(toUri(presigned.url()), presigned.expiration());
    }

    /** What S3 holds under {@code key}, or empty if nothing was uploaded there. */
    public Optional<StoredObject> find(String key) {
        try {
            HeadObjectResponse head = s3.headObject(request -> request.bucket(properties.bucket()).key(key));
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void delete(String key) {
        s3.deleteObject(request -> request.bucket(properties.bucket()).key(key));
    }

    /**
     * The browser sets Host and Content-Length itself (and may not set them by hand), so it is only
     * told the rest. S3 still checks all of them against the signature.
     */
    private static Map<String, String> browserHeaders(Map<String, List<String>> signedHeaders) {
        return signedHeaders.entrySet().stream()
                .filter(header -> !List.of("host", "content-length").contains(header.getKey().toLowerCase()))
                .collect(Collectors.toMap(Map.Entry::getKey, header -> String.join(",", header.getValue())));
    }

    /**
     * The original name for the browser's save dialog: an ASCII fallback for old clients plus the
     * exact name, percent-encoded (RFC 6266). Quotes, backslashes and control characters cannot
     * break out of the header.
     */
    static String contentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^A-Za-z0-9._ -]", "_");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                + UriUtils.encode(fileName, StandardCharsets.UTF_8);
    }

    private static URI toUri(URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The AWS SDK produced an invalid presigned URL", e);
        }
    }
}
