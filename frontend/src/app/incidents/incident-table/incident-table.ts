import { DatePipe, LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Incident } from '../incident.model';

/** Presentational table: renders whatever incidents it is given, holds no state. */
@Component({
  selector: 'app-incident-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, LowerCasePipe, RouterLink],
  templateUrl: './incident-table.html',
  styleUrl: './incident-table.scss',
})
export class IncidentTable {
  readonly incidents = input.required<Incident[]>();
}
