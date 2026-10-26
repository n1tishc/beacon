package dev.beacon.incident.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AttachmentPolicyTest {

    @Test
    void contentTypesAreComparedWithoutCaseOrParameters() {
        assertThat(AttachmentPolicy.normalize(" Text/Plain; charset=UTF-8")).isEqualTo("text/plain");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "image/svg+xml", "application/javascript", "application/octet-stream"})
    void typesABrowserCouldRunScriptInAreRejected(String type) {
        assertThatThrownBy(() -> AttachmentPolicy.check(type, 100))
                .isInstanceOf(AttachmentRejectedException.class);
    }

    @Test
    void theSizeLimitIsInclusive() {
        assertThatCode(() -> AttachmentPolicy.check("image/png", AttachmentPolicy.MAX_SIZE_BYTES))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AttachmentPolicy.check("image/png", AttachmentPolicy.MAX_SIZE_BYTES + 1))
                .hasMessage("Attachments are limited to 10 MB.");
    }
}
