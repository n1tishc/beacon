import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';

import { AuthStore } from './auth-store';

/**
 * Protected routes. `canMatch` (rather than `canActivate`) means a logged-out visitor never even
 * downloads the lazy chunk. They are sent to /login and brought back afterwards.
 */
export const authGuard: CanMatchFn = (_route, segments) => {
  if (inject(AuthStore).isAuthenticated()) {
    return true;
  }
  const returnUrl = '/' + segments.map((segment) => segment.path).join('/');
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl } });
};

/** The login page, for visitors who are not signed in yet. */
export const guestGuard: CanMatchFn = () =>
  inject(AuthStore).isAuthenticated() ? inject(Router).createUrlTree(['/incidents']) : true;
