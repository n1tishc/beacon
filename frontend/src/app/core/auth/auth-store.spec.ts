import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthStore } from './auth-store';
import { aSession } from './testing/auth-fixtures';

describe('AuthStore', () => {
  let store: AuthStore;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    store = TestBed.inject(AuthStore);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  function signIn(role: 'ADMIN' | 'RESPONDER' | 'VIEWER' = 'RESPONDER') {
    store.login('someone', 'secret').subscribe();
    http.expectOne('/api/auth/login').flush(aSession('token-from-login', role));
  }

  it('keeps the access token and user in memory after login', () => {
    signIn();

    expect(store.isAuthenticated()).toBe(true);
    expect(store.accessToken()).toBe('token-from-login');
    expect(store.user()?.role).toBe('RESPONDER');
  });

  it('applies the same role hierarchy as the backend', () => {
    signIn('RESPONDER');

    expect(store.hasRole('VIEWER')).toBe(true);
    expect(store.hasRole('RESPONDER')).toBe(true);
    expect(store.hasRole('ADMIN')).toBe(false);
  });

  it('shares one refresh request between concurrent callers', () => {
    const tokens: string[] = [];
    store.refresh().subscribe((token) => tokens.push(token));
    store.refresh().subscribe((token) => tokens.push(token));

    http.expectOne('/api/auth/refresh').flush(aSession('refreshed'));

    expect(tokens).toEqual(['refreshed', 'refreshed']);
    expect(store.accessToken()).toBe('refreshed');
  });

  it('restores the session from the refresh cookie at startup', async () => {
    const restored = store.restoreSession();
    http.expectOne('/api/auth/refresh').flush(aSession('restored'));

    await restored;
    expect(store.isAuthenticated()).toBe(true);
  });

  it('starts logged out when there is no valid refresh cookie', async () => {
    const restored = store.restoreSession();
    http
      .expectOne('/api/auth/refresh')
      .flush({ title: 'Session expired' }, { status: 401, statusText: 'Unauthorized' });

    await expect(restored).resolves.toBeUndefined();
    expect(store.isAuthenticated()).toBe(false);
  });

  it('logout revokes the session server-side and returns to the login page', () => {
    signIn();

    store.logout();

    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    expect(store.isAuthenticated()).toBe(false);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('an expired session sends the user to login with a way back', () => {
    signIn();
    vi.spyOn(router, 'url', 'get').mockReturnValue('/incidents');

    store.expireSession();

    expect(store.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/incidents' },
    });
  });
});
