import { ChannelStatus, IncidentNotification } from '../notification.model';

let nextId = 1;

export function aNotification(overrides: Partial<IncidentNotification> = {}): IncidentNotification {
  return {
    id: `00000000-0000-4000-8000-${String(nextId++).padStart(12, '0')}`,
    incidentId: '11111111-1111-4111-8111-111111111111',
    eventType: 'TRIGGERED',
    channel: 'SLACK',
    status: 'SENT',
    attempts: 1,
    detail: null,
    message: ':rotating_light: [HIGH] Checkout is down, triggered by riley',
    createdAt: '2026-10-25T12:00:00Z',
    updatedAt: '2026-10-25T12:00:01Z',
    ...overrides,
  };
}

export function aChannel(overrides: Partial<ChannelStatus> = {}): ChannelStatus {
  return { channel: 'SLACK', enabled: true, circuitState: 'CLOSED', ...overrides };
}
