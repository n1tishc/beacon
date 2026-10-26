import { Incident, Page, TimelineEntry } from '../incident.model';

export function anIncident(overrides: Partial<Incident> = {}): Incident {
  return {
    id: crypto.randomUUID(),
    version: 0,
    title: 'Checkout API returning 502s',
    description: null,
    severity: 'HIGH',
    status: 'TRIGGERED',
    createdAt: '2026-01-15T10:00:00Z',
    updatedAt: '2026-01-15T10:00:00Z',
    acknowledgedAt: null,
    resolvedAt: null,
    ...overrides,
  };
}

export function aPageOf(incidents: Incident[]): Page<Incident> {
  return { content: incidents, page: 0, size: 50, totalElements: incidents.length, totalPages: 1 };
}

let nextEntryId = 1;

export function aTimelineEntry(overrides: Partial<TimelineEntry> = {}): TimelineEntry {
  return {
    id: nextEntryId++,
    type: 'TRIGGERED',
    actor: 'riley',
    occurredAt: '2026-01-15T10:00:00Z',
    note: 'Severity HIGH',
    ...overrides,
  };
}
