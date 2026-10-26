import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { AuthStore } from '../core/auth/auth-store';
import { IncidentApi } from './incident-api';
import { IncidentStore } from './incident-store';
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
});
