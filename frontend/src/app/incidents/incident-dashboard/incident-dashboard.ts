import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';

import { CreateIncidentForm } from '../create-incident-form/create-incident-form';
import { IncidentStore } from '../incident-store';
import { IncidentTable } from '../incident-table/incident-table';

@Component({
  selector: 'app-incident-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CreateIncidentForm, IncidentTable],
  templateUrl: './incident-dashboard.html',
  styleUrl: './incident-dashboard.scss',
})
export class IncidentDashboard implements OnInit {
  protected readonly store = inject(IncidentStore);

  ngOnInit(): void {
    this.store.load();
  }
}
