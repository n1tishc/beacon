import { Routes } from '@angular/router';

import { authGuard, guestGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'incidents' },
  {
    path: 'login',
    canMatch: [guestGuard],
    loadComponent: () => import('./login/login-page').then((m) => m.LoginPage),
    title: 'Sign in · Beacon',
  },
  {
    path: 'incidents',
    canMatch: [authGuard],
    loadChildren: () => import('./incidents/incidents.routes').then((m) => m.INCIDENT_ROUTES),
  },
  { path: '**', redirectTo: 'incidents' },
];
