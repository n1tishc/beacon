import { DatePipe, DOCUMENT, LowerCasePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  Signal,
  inject,
  input,
  signal,
} from '@angular/core';

import { NotificationApi } from '../notification-api';
import {
  CHANNEL_LABELS,
  DeliveryStatus,
  IncidentNotification,
  NotifiedEvent,
} from '../notification.model';
import { pollWhileVisible } from '../poll-while-visible';

const EVENT_LABELS: Record<NotifiedEvent, string> = {
  TRIGGERED: 'Triggered',
  ACKNOWLEDGED: 'Acknowledged',
  RESOLVED: 'Resolved',
};

const STATUS_LABELS: Record<DeliveryStatus, string> = {
  PENDING: 'Sending…',
  SENT: 'Sent',
  FAILED: 'Failed',
  SKIPPED: 'Skipped',
};

/**
 * Who was told about this incident, and whether it reached them. Delivery finishes a few seconds
 * after the change that caused it, so the list keeps itself fresh while the page is open. It loads
 * on its own: if notification-service is down, only this panel says so; the incident still shows.
 */
@Component({
  selector: 'app-incident-notifications',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, LowerCasePipe],
  templateUrl: './incident-notifications.html',
  styleUrl: './incident-notifications.scss',
})
export class IncidentNotifications implements OnInit {
  readonly incidentId = input.required<string>();

  private readonly api = inject(NotificationApi);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  // Replaced in ngOnInit, once the input is set.
  protected notifications: Signal<IncidentNotification[] | null> = signal(null);
  protected failing: Signal<boolean> = signal(false);

  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly eventLabels = EVENT_LABELS;
  protected readonly statusLabels = STATUS_LABELS;

  ngOnInit(): void {
    const polled = pollWhileVisible(this.document, this.destroyRef, () =>
      this.api.forIncident(this.incidentId()),
    );
    this.notifications = polled.value;
    this.failing = polled.failing;
  }
}
