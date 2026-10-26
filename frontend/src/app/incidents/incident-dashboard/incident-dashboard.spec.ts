import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { Incident, Page } from '../incident.model';
import { aPageOf, anIncident } from '../testing/incident-fixtures';
import { IncidentDashboard } from './incident-dashboard';

describe('IncidentDashboard', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [IncidentDashboard],
      providers: [provideHttpClient(), provideHttpClientTesting()],
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
    expect(rows[0].querySelector('.severity')?.classList).toContain('severity-critical');
    expect(rows[0].querySelector('.status')?.textContent).toContain('TRIGGERED');
    expect(element.querySelector('.subtitle')?.textContent).toContain('2 incidents');
  });

  it('shows an empty state when there are no incidents', async () => {
    const element = await renderWith(aPageOf([]));

    expect(element.querySelector('.empty')?.textContent).toContain('No incidents yet');
  });
});
