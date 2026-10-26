import { DatePipe, LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthStore } from '../../core/auth/auth-store';
import { IncidentNotifications } from '../../notifications/incident-notifications/incident-notifications';
import { IncidentTimeline } from '../incident-timeline/incident-timeline';
import { canAcknowledge, canResolve } from '../incident.model';
import { IncidentDetailStore } from './incident-detail-store';

@Component({
  selector: 'app-incident-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    LowerCasePipe,
    ReactiveFormsModule,
    RouterLink,
    IncidentNotifications,
    IncidentTimeline,
  ],
  providers: [IncidentDetailStore],
  templateUrl: './incident-detail-page.html',
  styleUrl: './incident-detail-page.scss',
})
export class IncidentDetailPage implements OnInit {
  /** Bound from the `:id` route parameter (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly store = inject(IncidentDetailStore);
  private readonly auth = inject(AuthStore);
  private readonly fb = inject(NonNullableFormBuilder);

  /** Hiding controls is a courtesy; incident-service rejects a viewer's commands with 403 regardless. */
  protected readonly canRespond = computed(() => this.auth.hasRole('RESPONDER'));
  protected readonly showAcknowledge = computed(() => {
    const incident = this.store.incident();
    return this.canRespond() && !!incident && canAcknowledge(incident.status);
  });
  protected readonly showResolve = computed(() => {
    const incident = this.store.incident();
    return this.canRespond() && !!incident && canResolve(incident.status);
  });

  // Limits mirror the backend's ResolveRequest and AddNoteRequest.
  protected readonly resolveForm = this.fb.group({ note: ['', [Validators.maxLength(4000)]] });
  protected readonly noteForm = this.fb.group({
    text: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(4000)]],
  });

  ngOnInit(): void {
    this.store.load(this.id());
  }

  protected acknowledge(): void {
    this.store.acknowledge().subscribe();
  }

  protected resolve(): void {
    if (this.resolveForm.invalid) {
      return;
    }
    const note = this.resolveForm.getRawValue().note.trim() || null;
    this.store.resolve(note).subscribe((done) => done && this.resolveForm.reset());
  }

  protected addNote(): void {
    if (this.noteForm.invalid) {
      this.noteForm.markAllAsTouched();
      return;
    }
    const text = this.noteForm.getRawValue().text.trim();
    this.store.addNote(text).subscribe((done) => done && this.noteForm.reset());
  }
}
