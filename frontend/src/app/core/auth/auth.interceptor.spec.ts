import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthStore } from './auth-store';
import { authInterceptor } from './auth.interceptor';
import { aSession } from './testing/auth-fixtures';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthStore;

  const unauthorized = { status: 401, statusText: 'Unauthorized' };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthStore);
    vi.spyOn(auth, 'expireSession').mockImplementation(() => undefined);

    auth.login('riley', 'secret').subscribe();
    backend.expectOne('/api/auth/login').flush(aSession('token-1'));
  });

  afterEach(() => backend.verify());

  it('adds the bearer token to API requests', () => {
    http.get('/api/incidents').subscribe();

    const request = backend.expectOne('/api/incidents');
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-1');
    request.flush({});
  });

  it('never sends the token to other origins or to cookie-authenticated auth endpoints', () => {
    http.get('https://status.example.com/api/health').subscribe();
    http.post('/api/auth/refresh', null).subscribe();

    expect(
      backend
        .expectOne('https://status.example.com/api/health')
        .request.headers.has('Authorization'),
    ).toBe(false);
    const refresh = backend.expectOne('/api/auth/refresh');
    expect(refresh.request.headers.has('Authorization')).toBe(false);
    refresh.flush(aSession('ignored'));
  });

  it('refreshes once on 401 and retries the request with the new token', () => {
    let body: unknown;
    http.get('/api/incidents').subscribe((response) => (body = response));

    backend.expectOne('/api/incidents').flush(null, unauthorized);
    backend.expectOne('/api/auth/refresh').flush(aSession('token-2'));
    const retry = backend.expectOne('/api/incidents');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer token-2');
    retry.flush({ ok: true });

    expect(body).toEqual({ ok: true });
  });

  it('uses a single refresh for requests that fail together', () => {
    http.get('/api/incidents').subscribe();
    http.get('/api/incidents/42').subscribe();

    backend.expectOne('/api/incidents').flush(null, unauthorized);
    backend.expectOne('/api/incidents/42').flush(null, unauthorized);
    backend.expectOne('/api/auth/refresh').flush(aSession('token-2'));

    backend.expectOne('/api/incidents').flush({});
    backend.expectOne('/api/incidents/42').flush({});
  });

  it('ends the session when the refresh fails, without retrying', () => {
    let status: number | undefined;
    http.get('/api/incidents').subscribe({ error: (error) => (status = error.status) });

    backend.expectOne('/api/incidents').flush(null, unauthorized);
    backend.expectOne('/api/auth/refresh').flush(null, unauthorized);

    expect(auth.expireSession).toHaveBeenCalled();
    expect(status).toBe(401);
  });

  it('passes other errors through untouched', () => {
    let status: number | undefined;
    http.post('/api/incidents', {}).subscribe({ error: (error) => (status = error.status) });

    backend.expectOne('/api/incidents').flush(null, { status: 403, statusText: 'Forbidden' });

    expect(status).toBe(403);
    expect(auth.expireSession).not.toHaveBeenCalled();
  });
});
