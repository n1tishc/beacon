package dev.beacon.incident.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.beacon.incident.TestIncidents;

class PostmortemTest {

    private static final Instant NOW = Instant.parse("2026-10-26T09:00:00Z");
    private static final PostmortemContent CONTENT = new PostmortemContent("Checkout down", null, null, null);

    @Test
    void anOpenIncidentCannotHaveAPostmortemOrAttachments() {
        Incident open = TestIncidents.persisted("Checkout down", Severity.HIGH, NOW);

        assertThatThrownBy(() -> Postmortem.writeFor(open, CONTENT, "riley", NOW))
                .isInstanceOf(IncidentNotResolvedException.class);
        assertThatThrownBy(() -> Attachment.announce(open, "log.txt", "text/plain", 10, "riley", NOW))
                .isInstanceOf(IncidentNotResolvedException.class);
    }

    @Test
    void revisingKeepsTheAuthorAndRecordsTheEditor() {
        Incident incident = TestIncidents.persisted("Checkout down", Severity.HIGH, NOW);
        incident.resolve("riley", null, NOW);
        Postmortem postmortem = Postmortem.writeFor(incident, CONTENT, "riley", NOW);

        postmortem.revise(new PostmortemContent("Checkout down 9 min", "All EU users", null, null), "avery",
                NOW.plusSeconds(60));

        assertThat(postmortem.getCreatedBy()).isEqualTo("riley");
        assertThat(postmortem.getUpdatedBy()).isEqualTo("avery");
        assertThat(postmortem.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(postmortem.getContent().impact()).isEqualTo("All EU users");
    }

    @Test
    void theObjectKeyNeverContainsTheFileName() {
        Incident incident = TestIncidents.persisted("Checkout down", Severity.HIGH, NOW);
        incident.resolve("riley", null, NOW);

        Attachment attachment = Attachment.announce(incident, "../../etc/passwd", "text/plain", 10, "riley", NOW);

        assertThat(attachment.getObjectKey())
                .isEqualTo("incidents/" + incident.getId() + "/attachments/" + attachment.getId());
    }
}
