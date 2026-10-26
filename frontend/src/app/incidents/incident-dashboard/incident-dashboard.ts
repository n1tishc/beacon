import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { AuthStore } from '../../core/auth/auth-store';
import { ChannelStatusBar } from '../../notifications/channel-status/channel-status';
import { CreateIncidentForm } from '../create-incident-form/create-incident-form';
import { IncidentStore } from '../incident-store';
import { IncidentTable } from '../incident-table/incident-table';

@Component({
  selector: 'app-incident-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ChannelStatusBar, CreateIncidentForm, DatePipe, IncidentTable],
  templateUrl: './incident-dashboard.html',
  styleUrl: './incident-dashboard.scss',
})
export class IncidentDashboard implements OnInit {
  protected readonly store = inject(IncidentStore);
  private readonly auth = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);

  /** Hiding the form is a courtesy; incident-service rejects a viewer's POST with 403 regardless. */
  protected readonly canTrigger = computed(() => this.auth.hasRole('RESPONDER'));

  ngOnInit(): void {
    this.store.load();
    // Alerts open incidents without anyone touching this page, so it keeps itself up to date.
    this.store.live().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }
}
