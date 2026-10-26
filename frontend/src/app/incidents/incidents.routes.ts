import { Routes } from '@angular/router';

import { IncidentDashboard } from './incident-dashboard/incident-dashboard';

export const INCIDENT_ROUTES: Routes = [
  { path: '', component: IncidentDashboard, title: 'Incidents · Beacon' },
  {
    path: ':id',
    loadComponent: () =>
      import('./incident-detail/incident-detail-page').then((m) => m.IncidentDetailPage),
    title: 'Incident · Beacon',
  },
];
