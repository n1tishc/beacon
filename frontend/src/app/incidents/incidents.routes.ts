import { Routes } from '@angular/router';

import { IncidentDashboard } from './incident-dashboard/incident-dashboard';

export const INCIDENT_ROUTES: Routes = [
  { path: '', component: IncidentDashboard, title: 'Incidents · Beacon' },
];
