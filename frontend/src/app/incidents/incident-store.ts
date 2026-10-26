import { DOCUMENT } from '@angular/common';
import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { EMPTY, Observable, catchError, exhaustMap, finalize, map, tap } from 'rxjs';

import { AuthStore } from '../core/auth/auth-store';
import { fieldErrors, problemMessage } from '../core/problem-message';
import { LIVE_REFRESH_MS, whileVisible } from '../core/while-visible';
import { IncidentApi } from './incident-api';
import { CreateIncidentRequest, Incident, Page } from './incident.model';

interface IncidentState {
  incidents: Incident[];
  total: number;
  loading: boolean;
  saving: boolean;
  error: string | null;
  /** Server-side validation messages for the create form, keyed by field. */
  fieldErrors: Record<string, string>;
  /** When the list last came back from the server. */
  updatedAt: Date | null;
  /** The last background refresh failed; the list shown may be out of date. */
  liveFailing: boolean;
}

const initialState: IncidentState = {
  incidents: [],
  total: 0,
  loading: false,
  saving: false,
  error: null,
  fieldErrors: {},
  updatedAt: null,
  liveFailing: false,
};

/**
 * Signal-based store for the incident feature.
 * State is private and only changes through the methods below; components read the computed selectors.
 */
@Injectable({ providedIn: 'root' })
export class IncidentStore {
  private readonly api = inject(IncidentApi);
  private readonly auth = inject(AuthStore);
  private readonly document = inject(DOCUMENT);
  private readonly state = signal<IncidentState>(initialState);

  /**
   * Bumped on every local change to the list. A poll that started before the change may return a
   * list without it; such a response is dropped instead of briefly un-doing what the user just did.
   */
  private localChanges = 0;

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
  readonly updatedAt = computed(() => this.state().updatedAt);
  readonly liveFailing = computed(() => this.state().liveFailing);

  load(): void {
    this.patch({ loading: true, error: null });
    this.api.list().subscribe({
      next: (page) => this.applyPage(page),
      error: (error) => this.patch({ loading: false, error: problemMessage(error) }),
    });
  }

  /**
   * Keeps the list fresh while subscribed: polls every `periodMs`, but only while the browser tab is
   * visible, and refreshes straight away when the user comes back to it. Background refreshes never
   * show the loading state. A slow response is never stacked on or cancelled: `exhaustMap` skips
   * ticks while a request is still in flight. The caller owns the subscription (the dashboard ends it
   * when it is destroyed).
   */
  live(periodMs = LIVE_REFRESH_MS): Observable<void> {
    return whileVisible(this.document, periodMs).pipe(
      exhaustMap(() => {
        const changesBefore = this.localChanges;
        return this.api.list().pipe(
          tap((page) => {
            if (this.localChanges === changesBefore) {
              this.applyPage(page);
            }
          }),
          catchError(() => {
            this.patch({ liveFailing: true });
            return EMPTY;
          }),
        );
      }),
      map(() => undefined),
    );
  }

  /** Emits the created incident on success; on failure the error lands in `error()` and nothing is emitted. */
  create(request: CreateIncidentRequest): Observable<Incident> {
    this.patch({ saving: true, error: null, fieldErrors: {} });
    return this.api.create(request).pipe(
      tap((incident) => {
        this.localChanges++;
        this.state.update((state) => ({
          ...state,
          incidents: [incident, ...state.incidents],
          total: state.total + 1,
        }));
      }),
      catchError((error) => {
        this.patch({ error: problemMessage(error), fieldErrors: fieldErrors(error) });
        return EMPTY;
      }),
      finalize(() => this.patch({ saving: false })),
    );
  }

  private applyPage(page: Page<Incident>): void {
    this.patch({
      incidents: page.content,
      total: page.totalElements,
      loading: false,
      updatedAt: new Date(),
      liveFailing: false,
    });
  }

  private patch(partial: Partial<IncidentState>): void {
    this.state.update((state) => ({ ...state, ...partial }));
  }
}
