package dev.beacon.incident.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AttachmentStorageTest {

    @Test
    void plainNamesAreKept() {
        assertThat(AttachmentStorage.contentDisposition("heap-dump_2.png"))
                .isEqualTo("attachment; filename=\"heap-dump_2.png\"; filename*=UTF-8''heap-dump_2.png");
    }

    /** A crafted name must not be able to end the header value or add parameters. */
    @Test
    void quotesAndLineBreaksCannotEscapeTheHeader() {
        String header = AttachmentStorage.contentDisposition("x\"; filename=evil.html\r\nSet-Cookie: a=b");

        assertThat(header).doesNotContain("\r", "\n", "evil.html\"");
        assertThat(header).startsWith("attachment; filename=\"x__ filename_evil.html__Set-Cookie_ a_b\"; ");
    }

    @Test
    void nonAsciiNamesSurviveInTheEncodedForm() {
        assertThat(AttachmentStorage.contentDisposition("café.txt"))
                .isEqualTo("attachment; filename=\"caf_.txt\"; filename*=UTF-8''caf%C3%A9.txt");
    }
}
