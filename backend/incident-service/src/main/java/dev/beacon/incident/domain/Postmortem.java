package dev.beacon.incident.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The write-up of a resolved incident: one per incident, edited by whoever is investigating. Several
 * people may edit it over a few days, so it has its own optimistic lock, separate from the incident's.
 */
@Entity
@Table(name = "postmortems")
public class Postmortem {

    /** The incident's id: a postmortem has no identity of its own. */
    @Id
    @Column(name = "incident_id")
    private UUID incidentId;

    /** Same role as {@link Incident}'s version: null until the first insert, then checked on every update. */
    @Version
    private Long version;

    @Column(nullable = false, length = 4000)
    private String summary;

    @Column(length = 4000)
    private String impact;

    @Column(name = "root_cause", length = 4000)
    private String rootCause;

    @Column(name = "action_items", length = 4000)
    private String actionItems;

    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Postmortem() {
        // required by JPA
    }

    private Postmortem(UUID incidentId, String author, Instant now) {
        this.incidentId = incidentId;
        this.createdBy = author;
        this.createdAt = now;
    }

    /** Only a resolved incident gets a postmortem: before that, nobody knows how the story ends. */
    public static Postmortem writeFor(Incident incident, PostmortemContent content, String author, Instant now) {
        incident.requireResolved();
        Postmortem postmortem = new Postmortem(incident.getId(), author, now);
        postmortem.revise(content, author, now);
        return postmortem;
    }

    public void revise(PostmortemContent content, String editor, Instant now) {
        this.summary = content.summary();
        this.impact = content.impact();
        this.rootCause = content.rootCause();
        this.actionItems = content.actionItems();
        this.updatedBy = editor;
        this.updatedAt = now;
    }

    /** Rejects an edit made on an outdated copy, like {@link Incident#requireVersion}. */
    public void requireVersion(long expectedVersion) {
        if (version == null || version != expectedVersion) {
            throw new StalePostmortemException(incidentId, expectedVersion, version == null ? 0 : version);
        }
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public Long getVersion() {
        return version;
    }

    public PostmortemContent getContent() {
        return new PostmortemContent(summary, impact, rootCause, actionItems);
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
