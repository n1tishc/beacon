/** Mirrors notification-service's NotificationResponse and ChannelStatus. */

export type NotificationChannel = 'MOCK' | 'SLACK';

export type DeliveryStatus = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';

export type NotifiedEvent = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED';

/** Named to stay clear of the browser's own `Notification`. */
export interface IncidentNotification {
  id: string;
  incidentId: string;
  eventType: NotifiedEvent;
  channel: NotificationChannel;
  status: DeliveryStatus;
  attempts: number;
  /** Why delivery failed or was skipped. */
  detail: string | null;
  message: string;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelStatus {
  channel: NotificationChannel;
  enabled: boolean;
  /** Resilience4j circuit breaker state (CLOSED, OPEN, HALF_OPEN); null for a channel without one. */
  circuitState: string | null;
}

export const CHANNEL_LABELS: Record<NotificationChannel, string> = {
  MOCK: 'Mock sink',
  SLACK: 'Slack',
};
