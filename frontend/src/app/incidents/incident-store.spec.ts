import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, Subscription, of, throwError } from 'rxjs';

import { AuthStore } from '../core/auth/auth-store';
import { IncidentApi } from './incident-api';
import { IncidentStore, LIVE_REFRESH_MS } from './incident-store';
import { Incident, Page } from './incident.model';
import { aPageOf, anIncident } from './testing/incident-fixtures';

describe('IncidentStore', () => {
  let store: IncidentStore;
  let api: { list: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> };
  const isAuthenticated = signal(true);

  beforeEach(() => {
    api = { list: vi.fn(), create: vi.fn() };
    isAuthenticated.set(true);
    TestBed.configureTestingModule({
      providers: [
        { provide: IncidentApi, useValue: api },
        { provide: AuthStore, useValue: { isAuthenticated } },
      ],
    });
    store = TestBed.inject(IncidentStore);
  });

  it('loads incidents into state', () => {
    const incidents = [anIncident(), anIncident({ title: 'Disk full' })];
    api.list.mockReturnValue(of(aPageOf(incidents)));

    store.load();

    expect(store.incidents()).toEqual(incidents);
    expect(store.total()).toBe(2);
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeNull();
  });

  it('surfaces the problem detail when loading fails', () => {
    api.list.mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: 503, error: { detail: 'Database unavailable' } }),
      ),
    );

    store.load();

    expect(store.error()).toBe('Database unavailable');
    expect(store.loading()).toBe(false);
  });

  it('prepends a created incident and bumps the total', () => {
    const existing = anIncident({ title: 'Older' });
    const created = anIncident({ title: 'Newer' });
    api.list.mockReturnValue(of(aPageOf([existing])));
    api.create.mockReturnValue(of(created));
    store.load();

    let emitted: unknown;
    store
      .create({ title: 'Newer', description: null, severity: 'HIGH' })
      .subscribe((i) => (emitted = i));

    expect(emitted).toEqual(created);
    expect(store.incidents()).toEqual([created, existing]);
    expect(store.total()).toBe(2);
    expect(store.saving()).toBe(false);
  });

  it('records an error and emits nothing when creation fails', () => {
    api.create.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    const next = vi.fn();
    store.create({ title: 'x', description: null, severity: 'LOW' }).subscribe(next);

    expect(next).not.toHaveBeenCalled();
    expect(store.error()).toContain('Cannot reach the Beacon API');
    expect(store.saving()).toBe(false);
  });

  it('forgets everything when the user signs out', () => {
    api.list.mockReturnValue(of(aPageOf([anIncident()])));
    store.load();

    isAuthenticated.set(false);
    TestBed.tick();

    expect(store.incidents()).toEqual([]);
    expect(store.total()).toBe(0);
  });

  describe('live()', () => {
    let hidden: boolean;
    let live: Subscription;

    beforeEach(() => {
      vi.useFakeTimers();
      hidden = false;
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    });

    afterEach(() => {
      live?.unsubscribe();
      vi.useRealTimers();
    });

    function setHidden(value: boolean) {
      hidden = value;
      document.dispatchEvent(new Event('visibilitychange'));
    }

    it('polls every period without showing the loading state', () => {
      api.list.mockReturnValue(of(aPageOf([anIncident({ title: 'From the poll' })])));

      live = store.live().subscribe();
      expect(api.list).not.toHaveBeenCalled(); // the dashboard has just loaded; wait a period first

      vi.advanceTimersByTime(LIVE_REFRESH_MS);
      expect(api.list).toHaveBeenCalledTimes(1);
      expect(store.incidents()[0].title).toBe('From the poll');
      expect(store.loading()).toBe(false);
      expect(store.updatedAt()).not.toBeNull();

      vi.advanceTimersByTime(LIVE_REFRESH_MS * 2);
      expect(api.list).toHaveBeenCalledTimes(3);
    });

    it('pauses while the tab is hidden and refreshes as soon as it is visible again', () => {
      api.list.mockReturnValue(of(aPageOf([])));
      live = store.live().subscribe();

      setHidden(true);
      vi.advanceTimersByTime(LIVE_REFRESH_MS * 5);
      expect(api.list).not.toHaveBeenCalled();

      setHidden(false);
      vi.advanceTimersByTime(0);
      expect(api.list).toHaveBeenCalledTimes(1);
    });

    it('never stacks a request on one that is still in flight', () => {
      const slow = new Subject<Page<Incident>>();
      api.list.mockReturnValue(slow);
      live = store.live().subscribe();

      vi.advanceTimersByTime(LIVE_REFRESH_MS * 4);
      expect(api.list).toHaveBeenCalledTimes(1);

      slow.next(aPageOf([]));
      slow.complete();
      vi.advanceTimersByTime(LIVE_REFRESH_MS);
      expect(api.list).toHaveBeenCalledTimes(2);
    });

    it('drops a poll that started before the user created an incident', () => {
      const inFlight = new Subject<Page<Incident>>();
      api.list.mockReturnValue(inFlight);
      const created = anIncident({ title: 'Just created' });
      api.create.mockReturnValue(of(created));
      live = store.live().subscribe();
      vi.advanceTimersByTime(LIVE_REFRESH_MS); // poll starts

      store.create({ title: 'Just created', description: null, severity: 'HIGH' }).subscribe();
      inFlight.next(aPageOf([])); // the server's answer from before the create
      inFlight.complete();

      expect(store.incidents()).toEqual([created]);
    });

    it('flags failing refreshes and recovers on the next success', () => {
      api.list.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
      api.list.mockReturnValue(of(aPageOf([])));
      live = store.live().subscribe();

      vi.advanceTimersByTime(LIVE_REFRESH_MS);
      expect(store.liveFailing()).toBe(true);
      expect(store.error()).toBeNull();

      vi.advanceTimersByTime(LIVE_REFRESH_MS);
      expect(store.liveFailing()).toBe(false);
    });
  });
});
