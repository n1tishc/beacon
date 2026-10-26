import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  Observable,
  catchError,
  finalize,
  firstValueFrom,
  map,
  of,
  shareReplay,
  tap,
  timeout,
} from 'rxjs';

import { AuthApi } from './auth-api';
import { Role, SessionResponse, User } from './auth.model';

interface Session {
  accessToken: string;
  user: User;
}

/** Mirrors the backend role hierarchy: ADMIN > RESPONDER > VIEWER. */
const ROLE_RANK: Record<Role, number> = { VIEWER: 0, RESPONDER: 1, ADMIN: 2 };

/**
 * Who is signed in, and their access token.
 *
 * The access token lives only in memory (never localStorage), so an XSS payload cannot lift it
 * from storage. The refresh token is an HttpOnly cookie the browser sends to /api/auth/refresh;
 * a page reload restores the session through that endpoint (see {@link restoreSession}).
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly api = inject(AuthApi);
  private readonly router = inject(Router);
  private readonly session = signal<Session | null>(null);
  private refreshInFlight: Observable<string> | null = null;

  readonly user = computed(() => this.session()?.user ?? null);
  readonly isAuthenticated = computed(() => this.session() !== null);

  accessToken(): string | null {
    return this.session()?.accessToken ?? null;
  }

  /** True when the user holds `role` or a role above it. UI hint only; the API enforces roles itself. */
  hasRole(role: Role): boolean {
    const user = this.user();
    return user !== null && ROLE_RANK[user.role] >= ROLE_RANK[role];
  }

  login(username: string, password: string): Observable<User> {
    return this.api.login(username, password).pipe(
      tap((response) => this.start(response)),
      map((response) => response.user),
    );
  }

  /**
   * Gets a new access token using the refresh cookie. Concurrent callers (e.g. several requests
   * that all hit a 401 at once) share one HTTP call, because each refresh rotates the cookie and
   * the backend treats a reused cookie as theft.
   */
  refresh(): Observable<string> {
    this.refreshInFlight ??= this.api.refresh().pipe(
      tap((response) => this.start(response)),
      map((response) => response.accessToken),
      finalize(() => (this.refreshInFlight = null)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.refreshInFlight;
  }

  /** Run once at startup. Always resolves: no cookie, an expired one, or a down backend all mean "logged out". */
  restoreSession(): Promise<void> {
    return firstValueFrom(
      this.refresh().pipe(
        timeout(5000),
        map(() => undefined),
        catchError(() => of(undefined)),
      ),
    );
  }

  logout(): void {
    // Revoking server-side is best effort; the local session ends regardless.
    this.api.logout().subscribe({ error: () => undefined });
    this.session.set(null);
    void this.router.navigateByUrl('/login');
  }

  /** The session could not be renewed: sign in again, then come back to where the user was. */
  expireSession(): void {
    const returnUrl = this.router.url;
    this.session.set(null);
    const queryParams = returnUrl.startsWith('/login') ? {} : { returnUrl };
    void this.router.navigate(['/login'], { queryParams });
  }

  private start(response: SessionResponse): void {
    this.session.set({ accessToken: response.accessToken, user: response.user });
  }
}
