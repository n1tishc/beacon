export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export type IncidentStatus = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED';

export const SEVERITIES: readonly Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

export interface Incident {
  id: string;
  title: string;
  description: string | null;
  severity: Severity;
  status: IncidentStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateIncidentRequest {
  title: string;
  description: string | null;
  severity: Severity;
}

/** Mirrors the backend's PageResponse record. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
