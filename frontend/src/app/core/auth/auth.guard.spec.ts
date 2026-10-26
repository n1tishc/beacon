import { TestBed } from '@angular/core/testing';
import { CanMatchFn, Route, Router, UrlSegment, UrlTree, provideRouter } from '@angular/router';

import { AuthStore } from './auth-store';
import { authGuard, guestGuard } from './auth.guard';

describe('auth guards', () => {
  let authenticated: boolean;

  beforeEach(() => {
    authenticated = false;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthStore, useValue: { isAuthenticated: () => authenticated } },
      ],
    });
  });

  const segments = [new UrlSegment('incidents', {})];

  function run(guard: CanMatchFn) {
    const snapshot = {} as Parameters<CanMatchFn>[2]; // unused by our guards
    return TestBed.runInInjectionContext(() => guard({} as Route, segments, snapshot));
  }

  function serialize(result: unknown): string {
    return TestBed.inject(Router).serializeUrl(result as UrlTree);
  }

  it('lets signed-in users into protected routes', () => {
    authenticated = true;

    expect(run(authGuard)).toBe(true);
  });

  it('sends visitors to login, remembering where they were going', () => {
    expect(serialize(run(authGuard))).toBe('/login?returnUrl=%2Fincidents');
  });

  it('keeps signed-in users away from the login page', () => {
    authenticated = true;

    expect(serialize(run(guestGuard))).toBe('/incidents');
  });

  it('shows the login page to visitors', () => {
    expect(run(guestGuard)).toBe(true);
  });
});
