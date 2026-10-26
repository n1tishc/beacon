import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';

import { AuthStore } from '../../core/auth/auth-store';
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
  private readonly auth = inject(AuthStore);

  /** Hiding the form is a courtesy; incident-service rejects a viewer's POST with 403 regardless. */
  protected readonly canTrigger = computed(() => this.auth.hasRole('RESPONDER'));

  ngOnInit(): void {
    this.store.load();
  }
}
