import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, defer, finalize, forkJoin, map, of, switchMap, tap } from 'rxjs';

import { problemMessage, problemTitle } from '../../core/problem-message';
import { IncidentApi } from '../incident-api';
import { Incident, TimelineEntry } from '../incident.model';

interface IncidentDetailState {
  incident: Incident | null;
  timeline: TimelineEntry[];
  loading: boolean;
  /** A command (acknowledge, resolve, note) is in flight. */
  busy: boolean;
  error: string | null;
  /** Shown after the incident was reloaded because someone else changed it. */
  notice: string | null;
}

const initialState: IncidentDetailState = {
  incident: null,
  timeline: [],
  loading: false,
  busy: false,
  error: null,
  notice: null,
};

/** The backend's problem title for a 409 caused by a stale version (ApiExceptionHandler.INCIDENT_CHANGED). */
const INCIDENT_CHANGED = 'Incident changed';

const STALE_NOTICE =
  'Someone else updated this incident while you were looking at it. ' +
  'It has been reloaded; check it and try again.';

/**
 * State for one incident's detail page. Provided by the page component, not in root, so it lives and
 * dies with the page and two pages never share state.
 *
 * Every command sends the version the user is looking at. If someone else changed the incident in
 * the meantime the server answers 409 "Incident changed"; the store then reloads, so the user sees
 * the current state and decides again instead of acting on what they saw before.
 */
@Injectable()
export class IncidentDetailStore {
  private readonly api = inject(IncidentApi);
  private readonly state = signal<IncidentDetailState>(initialState);

  readonly incident = computed(() => this.state().incident);
  readonly timeline = computed(() => this.state().timeline);
  readonly loading = computed(() => this.state().loading);
  readonly busy = computed(() => this.state().busy);
  readonly error = computed(() => this.state().error);
  readonly notice = computed(() => this.state().notice);

  load(id: string): void {
    this.patch({ loading: true, error: null });
    this.fetch(id)
      .pipe(finalize(() => this.patch({ loading: false })))
      .subscribe({ error: (error) => this.patch({ error: problemMessage(error) }) });
  }

  // Commands return a cold Observable, like IncidentStore.create: nothing happens until the caller
  // subscribes. Each emits true once the command succeeded and the page has been reloaded, false otherwise.

  acknowledge(): Observable<boolean> {
    return this.run((incident) => this.api.acknowledge(incident));
  }

  resolve(note: string | null): Observable<boolean> {
    return this.run((incident) => this.api.resolve(incident, note));
  }

  addNote(text: string): Observable<boolean> {
    return this.run((incident) => this.api.addNote(incident.id, text));
  }

  /** Runs a command, then reloads the incident and its timeline so both reflect it. */
  private run(command: (incident: Incident) => Observable<unknown>): Observable<boolean> {
    return defer(() => {
      const incident = this.incident();
      if (!incident || this.busy()) {
        return of(false);
      }
      this.patch({ busy: true, error: null, notice: null });
      return command(incident).pipe(
        switchMap(() => this.fetch(incident.id)),
        map(() => true),
        catchError((error) => this.handleCommandError(incident.id, error)),
        finalize(() => this.patch({ busy: false })),
      );
    });
  }

  private handleCommandError(id: string, error: unknown): Observable<boolean> {
    if (problemTitle(error) !== INCIDENT_CHANGED) {
      this.patch({ error: problemMessage(error) });
      return of(false);
    }
    // Reload first, so the notice never sits on top of the outdated state it is warning about.
    return this.fetch(id).pipe(
      tap(() => this.patch({ notice: STALE_NOTICE })),
      map(() => false),
      catchError((reloadError) => {
        this.patch({ error: problemMessage(reloadError) });
        return of(false);
      }),
    );
  }

  private fetch(id: string): Observable<void> {
    return forkJoin({ incident: this.api.get(id), timeline: this.api.timeline(id) }).pipe(
      tap(({ incident, timeline }) => this.patch({ incident, timeline })),
      map(() => undefined),
    );
  }

  private patch(partial: Partial<IncidentDetailState>): void {
    this.state.update((state) => ({ ...state, ...partial }));
  }
}
