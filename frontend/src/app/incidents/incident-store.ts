import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { EMPTY, Observable, catchError, finalize, tap } from 'rxjs';

import { AuthStore } from '../core/auth/auth-store';
import { fieldErrors, problemMessage } from '../core/problem-message';
import { IncidentApi } from './incident-api';
import { CreateIncidentRequest, Incident } from './incident.model';

interface IncidentState {
  incidents: Incident[];
  total: number;
  loading: boolean;
  saving: boolean;
  error: string | null;
  /** Server-side validation messages for the create form, keyed by field. */
  fieldErrors: Record<string, string>;
}

const initialState: IncidentState = {
  incidents: [],
  total: 0,
  loading: false,
  saving: false,
  error: null,
  fieldErrors: {},
};

/**
 * Signal-based store for the incident feature.
 * State is private and only changes through the methods below; components read the computed selectors.
 */
@Injectable({ providedIn: 'root' })
export class IncidentStore {
  private readonly api = inject(IncidentApi);
  private readonly auth = inject(AuthStore);
  private readonly state = signal<IncidentState>(initialState);

  constructor() {
    // Never show one user's data to the next person who signs in on this tab.
    effect(() => {
      if (!this.auth.isAuthenticated()) {
        untracked(() => this.state.set(initialState));
      }
    });
  }

  readonly incidents = computed(() => this.state().incidents);
  readonly total = computed(() => this.state().total);
  readonly loading = computed(() => this.state().loading);
  readonly saving = computed(() => this.state().saving);
  readonly error = computed(() => this.state().error);
  readonly fieldErrors = computed(() => this.state().fieldErrors);

  load(): void {
    this.patch({ loading: true, error: null });
    this.api.list().subscribe({
      next: (page) =>
        this.patch({ incidents: page.content, total: page.totalElements, loading: false }),
      error: (error) => this.patch({ loading: false, error: problemMessage(error) }),
    });
  }

  /** Emits the created incident on success; on failure the error lands in `error()` and nothing is emitted. */
  create(request: CreateIncidentRequest): Observable<Incident> {
    this.patch({ saving: true, error: null, fieldErrors: {} });
    return this.api.create(request).pipe(
      tap((incident) =>
        this.state.update((state) => ({
          ...state,
          incidents: [incident, ...state.incidents],
          total: state.total + 1,
        })),
      ),
      catchError((error) => {
        this.patch({ error: problemMessage(error), fieldErrors: fieldErrors(error) });
        return EMPTY;
      }),
      finalize(() => this.patch({ saving: false })),
    );
  }

  private patch(partial: Partial<IncidentState>): void {
    this.state.update((state) => ({ ...state, ...partial }));
  }
}
