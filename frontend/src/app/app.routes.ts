import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'incidents' },
  {
    path: 'incidents',
    loadChildren: () => import('./incidents/incidents.routes').then((m) => m.INCIDENT_ROUTES),
  },
  { path: '**', redirectTo: 'incidents' },
];
