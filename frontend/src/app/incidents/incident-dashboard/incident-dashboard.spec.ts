import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthStore } from '../../core/auth/auth-store';
import { Role } from '../../core/auth/auth.model';
import { Incident, Page } from '../incident.model';
import { aPageOf, anIncident } from '../testing/incident-fixtures';
import { IncidentDashboard } from './incident-dashboard';

describe('IncidentDashboard', () => {
  let http: HttpTestingController;
  let role: Role;

  beforeEach(async () => {
    role = 'RESPONDER';
    const rank: Record<Role, number> = { VIEWER: 0, RESPONDER: 1, ADMIN: 2 };
    await TestBed.configureTestingModule({
      imports: [IncidentDashboard],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthStore,
          useValue: {
            isAuthenticated: signal(true),
            hasRole: (required: Role) => rank[role] >= rank[required],
          },
        },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function renderWith(response: Page<Incident>) {
    const fixture = TestBed.createComponent(IncidentDashboard);
    fixture.detectChanges();
    http.expectOne((request) => request.url === '/api/incidents').flush(response);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders one row per incident with severity and status', async () => {
    const element = await renderWith(
      aPageOf([
        anIncident({ title: 'Payments down', severity: 'CRITICAL' }),
        anIncident({ title: 'Slow search', severity: 'LOW' }),
      ]),
    );

    const rows = element.querySelectorAll('[data-testid="incident-row"]');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Payments down');
    expect(rows[0].querySelector('a.title')?.getAttribute('href')).toMatch(/^\/[0-9a-f-]{36}$/);
    expect(rows[0].querySelector('.severity')?.classList).toContain('severity-critical');
    expect(rows[0].querySelector('.status')?.textContent).toContain('TRIGGERED');
    expect(element.querySelector('.subtitle')?.textContent).toContain('2 incidents');
  });

  it('shows an empty state when there are no incidents', async () => {
    const element = await renderWith(aPageOf([]));

    expect(element.querySelector('.empty')?.textContent).toContain('No incidents yet');
  });

  it('offers the trigger form to responders', async () => {
    const element = await renderWith(aPageOf([]));

    expect(element.querySelector('app-create-incident-form')).not.toBeNull();
  });

  it('hides the trigger form from viewers', async () => {
    role = 'VIEWER';
    const element = await renderWith(aPageOf([]));

    expect(element.querySelector('app-create-incident-form')).toBeNull();
  });

  it('says the list is live once loaded, and shows where alert-opened incidents came from', async () => {
    const element = await renderWith(
      aPageOf([
        anIncident({ title: 'Payments down', source: 'grafana', alertCount: 3 }),
        anIncident({ title: 'Opened by hand' }),
      ]),
    );

    expect(element.querySelector('[data-testid="live-status"]')?.textContent).toContain('Live');
    const rows = element.querySelectorAll('[data-testid="incident-row"]');
    expect(rows[0].querySelector('[data-testid="origin"]')?.textContent).toContain(
      'grafana · 3 alerts',
    );
    expect(rows[1].querySelector('[data-testid="origin"]')).toBeNull();
  });
});
