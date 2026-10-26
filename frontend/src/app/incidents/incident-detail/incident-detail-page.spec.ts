import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthStore } from '../../core/auth/auth-store';
import { Role } from '../../core/auth/auth.model';
import { Incident, TimelineEntry } from '../incident.model';
import { aTimelineEntry, anIncident } from '../testing/incident-fixtures';
import { IncidentDetailPage } from './incident-detail-page';

describe('IncidentDetailPage', () => {
  let http: HttpTestingController;
  let role: Role;

  beforeEach(async () => {
    role = 'RESPONDER';
    const rank: Record<Role, number> = { VIEWER: 0, RESPONDER: 1, ADMIN: 2 };
    await TestBed.configureTestingModule({
      imports: [IncidentDetailPage],
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

  async function render(incident: Incident, timeline: TimelineEntry[] = [aTimelineEntry()]) {
    const fixture = TestBed.createComponent(IncidentDetailPage);
    fixture.componentRef.setInput('id', incident.id);
    fixture.detectChanges();
    respond(incident, timeline);
    await fixture.whenStable();
    // The notifications panel appears with the incident and loads on its own (it has its own spec).
    http.expectOne((request) => request.url === '/api/notifications').flush([]);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  function respond(incident: Incident, timeline: TimelineEntry[]) {
    http.expectOne(`/api/incidents/${incident.id}`).flush(incident);
    http.expectOne(`/api/incidents/${incident.id}/timeline`).flush(timeline);
  }

  const testId = (element: HTMLElement, id: string) =>
    element.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  it('shows the incident and its timeline', async () => {
    const { element } = await render(anIncident({ title: 'Payments down' }), [
      aTimelineEntry({ actor: 'riley', note: 'Severity CRITICAL' }),
      aTimelineEntry({ type: 'NOTE', actor: 'avery', note: 'Paged the DB team' }),
    ]);

    expect(element.querySelector('h1')?.textContent).toContain('Payments down');
    const entries = element.querySelectorAll('[data-testid="timeline-entry"]');
    expect(entries).toHaveLength(2);
    expect(entries[0].textContent).toContain('riley triggered the incident');
    expect(entries[1].textContent).toContain('avery added a note');
    expect(entries[1].textContent).toContain('Paged the DB team');
  });

  it('offers acknowledge and resolve on a triggered incident', async () => {
    const { element } = await render(anIncident({ status: 'TRIGGERED' }));

    expect(testId(element, 'acknowledge')).not.toBeNull();
    expect(testId(element, 'resolve')).not.toBeNull();
  });

  it('offers only resolve once acknowledged, and nothing once resolved', async () => {
    const acknowledged = await render(anIncident({ status: 'ACKNOWLEDGED' }));
    expect(testId(acknowledged.element, 'acknowledge')).toBeNull();
    expect(testId(acknowledged.element, 'resolve')).not.toBeNull();

    const resolved = await render(anIncident({ status: 'RESOLVED' }));
    expect(testId(resolved.element, 'resolve')).toBeNull();
    expect(resolved.element.textContent).toContain('This incident is resolved.');
  });

  it('gives viewers no way to change the incident', async () => {
    role = 'VIEWER';
    const { element } = await render(anIncident({ status: 'TRIGGERED' }));

    expect(testId(element, 'acknowledge')).toBeNull();
    expect(testId(element, 'resolve')).toBeNull();
    expect(element.querySelector('#note-text')).toBeNull();
  });

  it('acknowledges, then shows the reloaded state', async () => {
    const incident = anIncident({ id: 'abc', version: 4 });
    const { fixture, element } = await render(incident);

    testId(element, 'acknowledge')!.click();
    const request = http.expectOne('/api/incidents/abc/acknowledge');
    expect(request.request.body).toEqual({ version: 4 });
    request.flush({ ...incident, status: 'ACKNOWLEDGED', version: 5 });
    respond({ ...incident, status: 'ACKNOWLEDGED', version: 5 }, [
      aTimelineEntry(),
      aTimelineEntry({ type: 'ACKNOWLEDGED', note: null }),
    ]);
    await fixture.whenStable();

    expect(testId(element, 'status')?.textContent).toContain('ACKNOWLEDGED');
    expect(element.querySelectorAll('[data-testid="timeline-entry"]')).toHaveLength(2);
  });

  it('tells the user when their copy was stale and shows the current one', async () => {
    const incident = anIncident({ id: 'abc', version: 0 });
    const { fixture, element } = await render(incident);

    testId(element, 'resolve')!.click();
    http
      .expectOne('/api/incidents/abc/resolve')
      .flush(
        { title: 'Incident changed', detail: 'Incident abc was changed by someone else' },
        { status: 409, statusText: 'Conflict' },
      );
    respond({ ...incident, status: 'ACKNOWLEDGED', version: 1 }, [aTimelineEntry()]);
    await fixture.whenStable();

    expect(testId(element, 'notice')?.textContent).toContain('Someone else updated this incident');
    expect(testId(element, 'status')?.textContent).toContain('ACKNOWLEDGED');
  });

  it('shows the alert source, count and fingerprint for alert-opened incidents', async () => {
    const { element } = await render(
      anIncident({
        source: 'grafana',
        alertCount: 4,
        fingerprint: 'abc123',
        lastAlertAt: '2026-01-15T10:05:00Z',
      }),
    );

    expect(testId(element, 'source')?.textContent).toContain('grafana');
    expect(testId(element, 'alert-count')?.textContent).toContain('4');
    expect(element.querySelector('.fingerprint')?.textContent).toContain('abc123');
  });
});
