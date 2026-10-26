import { Incident, Page } from '../incident.model';

export function anIncident(overrides: Partial<Incident> = {}): Incident {
  return {
    id: crypto.randomUUID(),
    title: 'Checkout API returning 502s',
    description: null,
    severity: 'HIGH',
    status: 'TRIGGERED',
    createdAt: '2026-01-15T10:00:00Z',
    updatedAt: '2026-01-15T10:00:00Z',
    ...overrides,
  };
}

export function aPageOf(incidents: Incident[]): Page<Incident> {
  return { content: incidents, page: 0, size: 50, totalElements: incidents.length, totalPages: 1 };
}
