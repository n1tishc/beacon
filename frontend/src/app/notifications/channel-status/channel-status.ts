import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core';

import { NotificationApi } from '../notification-api';
import { CHANNEL_LABELS, ChannelStatus } from '../notification.model';
import { pollWhileVisible } from '../poll-while-visible';

/** What a responder should read from each circuit breaker state. */
const CIRCUIT_LABELS: Record<string, string> = {
  CLOSED: 'OK',
  OPEN: 'Circuit open, calls paused',
  HALF_OPEN: 'Recovering',
};

/** One chip per notification channel, with Slack's circuit breaker state. Hidden if unreachable. */
@Component({
  selector: 'app-channel-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './channel-status.html',
  styleUrl: './channel-status.scss',
})
export class ChannelStatusBar {
  private readonly api = inject(NotificationApi);

  protected readonly channels = pollWhileVisible(inject(DOCUMENT), inject(DestroyRef), () =>
    this.api.channels(),
  );
  protected readonly channelLabels = CHANNEL_LABELS;

  protected describe(channel: ChannelStatus): string {
    if (!channel.enabled) {
      return 'Not configured';
    }
    if (channel.circuitState === null) {
      return 'OK';
    }
    return CIRCUIT_LABELS[channel.circuitState] ?? channel.circuitState;
  }

  protected healthy(channel: ChannelStatus): boolean {
    return channel.enabled && (channel.circuitState === null || channel.circuitState === 'CLOSED');
  }
}
