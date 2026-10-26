package dev.beacon.incident.domain;

/**
 * What a postmortem says. Only the summary is required; the other sections are often filled in over
 * the days after an incident, as the investigation goes on.
 */
public record PostmortemContent(String summary, String impact, String rootCause, String actionItems) {
}
