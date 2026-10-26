import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TimelineEntry, TimelineEntryType } from '../incident.model';

const VERBS: Record<TimelineEntryType, string> = {
  TRIGGERED: 'triggered the incident',
  ACKNOWLEDGED: 'acknowledged',
  RESOLVED: 'resolved',
  NOTE: 'added a note',
};

/** Presentational: renders timeline entries oldest first, as the API returns them. */
@Component({
  selector: 'app-incident-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  templateUrl: './incident-timeline.html',
  styleUrl: './incident-timeline.scss',
})
export class IncidentTimeline {
  readonly entries = input.required<TimelineEntry[]>();

  protected verb(type: TimelineEntryType): string {
    return VERBS[type];
  }
}
