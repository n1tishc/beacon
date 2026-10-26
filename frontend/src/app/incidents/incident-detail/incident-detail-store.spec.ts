import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';

import { IncidentApi } from '../incident-api';
import { Incident } from '../incident.model';
import { aTimelineEntry, anIncident } from '../testing/incident-fixtures';
import { IncidentDetailStore } from './incident-detail-store';

function conflict(title: string): Observable<never> {
  return throwError(
    () => new HttpErrorResponse({ status: 409, error: { title, detail: `${title} (detail)` } }),
  );
}

describe('IncidentDetailStore', () => {
  let store: IncidentDetailStore;
  let api: Record<
    'get' | 'timeline' | 'acknowledge' | 'resolve' | 'addNote',
    ReturnType<typeof vi.fn>
  >;
  let current: Incident;

  beforeEach(() => {
    current = anIncident({ id: 'abc', version: 0 });
    api = {
      get: vi.fn(() => of(current)),
      timeline: vi.fn(() => of([aTimelineEntry()])),
      acknowledge: vi.fn(),
      resolve: vi.fn(),
      addNote: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [IncidentDetailStore, { provide: IncidentApi, useValue: api }],
    });
    store = TestBed.inject(IncidentDetailStore);
    store.load('abc');
  });

  it('loads the incident and its timeline together', () => {
    expect(store.incident()).toEqual(current);
    expect(store.timeline()).toHaveLength(1);
    expect(store.loading()).toBe(false);
  });

  it('acknowledges with the version on screen, then reloads incident and timeline', () => {
    api.acknowledge.mockImplementation(() => {
      current = { ...current, status: 'ACKNOWLEDGED', version: 1 };
      return of(current);
    });
    api.timeline.mockReturnValue(of([aTimelineEntry(), aTimelineEntry({ type: 'ACKNOWLEDGED' })]));

    let done: boolean | undefined;
    store.acknowledge().subscribe((result) => (done = result));

    expect(api.acknowledge).toHaveBeenCalledWith(expect.objectContaining({ version: 0 }));
    expect(done).toBe(true);
    expect(store.incident()?.status).toBe('ACKNOWLEDGED');
    expect(store.timeline().map((entry) => entry.type)).toEqual(['TRIGGERED', 'ACKNOWLEDGED']);
    expect(store.busy()).toBe(false);
  });

  it('reloads and explains when someone else changed the incident first', () => {
    current = { ...current, status: 'ACKNOWLEDGED', version: 1 }; // what the server now has
    api.resolve.mockReturnValue(conflict('Incident changed'));

    let done: boolean | undefined;
    store.resolve(null).subscribe((result) => (done = result));

    expect(done).toBe(false);
    expect(store.notice()).toContain('Someone else updated this incident');
    expect(store.error()).toBeNull();
    expect(store.incident()?.version).toBe(1);
  });

  it('shows other errors without reloading', () => {
    api.get.mockClear();
    api.acknowledge.mockReturnValue(conflict('Transition not allowed'));

    store.acknowledge().subscribe();

    expect(store.error()).toBe('Transition not allowed (detail)');
    expect(store.notice()).toBeNull();
    expect(api.get).not.toHaveBeenCalled();
  });

  it('does nothing until subscribed, and ignores a second command while one is in flight', () => {
    const pending = new Observable<never>(() => undefined); // never completes
    api.addNote.mockReturnValue(pending);

    const command = store.addNote('first');
    expect(api.addNote).not.toHaveBeenCalled();

    command.subscribe();
    expect(store.busy()).toBe(true);

    let second: boolean | undefined;
    store.addNote('second').subscribe((result) => (second = result));
    expect(second).toBe(false);
    expect(api.addNote).toHaveBeenCalledTimes(1);
  });

  it('reports a failed load', () => {
    api.get.mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: 404, error: { title: 'Incident not found' } }),
      ),
    );

    store.load('missing');

    expect(store.error()).toBe('Incident not found');
    expect(store.loading()).toBe(false);
  });
});
