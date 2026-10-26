import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { SessionResponse } from './auth.model';

/** Endpoints authenticated by the refresh cookie rather than a bearer token. */
export const COOKIE_AUTH_ENDPOINTS: readonly string[] = [
  '/api/auth/login',
  '/api/auth/refresh',
  '/api/auth/logout',
];

@Injectable({ providedIn: 'root' })
export class AuthApi {
  private readonly http = inject(HttpClient);

  login(username: string, password: string): Observable<SessionResponse> {
    return this.http.post<SessionResponse>('/api/auth/login', { username, password });
  }

  /** The browser attaches the HttpOnly refresh cookie; the response carries a new access token. */
  refresh(): Observable<SessionResponse> {
    return this.http.post<SessionResponse>('/api/auth/refresh', null);
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', null);
  }
}
