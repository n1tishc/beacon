export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export type IncidentStatus = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED';

export const SEVERITIES: readonly Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

export interface Incident {
  id: string;
  /** Sent back with acknowledge/resolve so the server can reject commands based on a stale copy. */
  version: number;
  title: string;
  description: string | null;
  severity: Severity;
  status: IncidentStatus;
  createdAt: string;
  updatedAt: string;
  acknowledgedAt: string | null;
  resolvedAt: string | null;
}

/**
 * The moves the backend's state machine allows from each status (IncidentStatus.java).
 * The UI only offers these; the server enforces them regardless.
 */
export function canAcknowledge(status: IncidentStatus): boolean {
  return status === 'TRIGGERED';
}

export function canResolve(status: IncidentStatus): boolean {
  return status !== 'RESOLVED';
}

export interface CreateIncidentRequest {
  title: string;
  description: string | null;
  severity: Severity;
}

export type TimelineEntryType = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED' | 'NOTE';

export interface TimelineEntry {
  id: number;
  type: TimelineEntryType;
  actor: string;
  occurredAt: string;
  note: string | null;
}

/** Mirrors the backend's PageResponse record. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
