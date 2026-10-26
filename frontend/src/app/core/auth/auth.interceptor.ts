import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';

import { COOKIE_AUTH_ENDPOINTS } from './auth-api';
import { AuthStore } from './auth-store';

/**
 * Adds the access token to API calls. When the API answers 401 (token expired), refreshes once
 * and retries the request once; if the refresh fails, the session is over and the user is sent
 * to the login page.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/') || COOKIE_AUTH_ENDPOINTS.includes(request.url)) {
    // Not our API, or a cookie-authenticated auth endpoint that must never trigger a refresh loop.
    return next(request);
  }

  const auth = inject(AuthStore);
  return next(withToken(request, auth.accessToken())).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        catchError(() => {
          auth.expireSession();
          return throwError(() => error);
        }),
        switchMap((token) => next(withToken(request, token))),
      );
    }),
  );
};

function withToken(request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
}
