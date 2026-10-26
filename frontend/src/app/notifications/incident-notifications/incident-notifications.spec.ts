import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { LIVE_REFRESH_MS } from '../../core/while-visible';
import { IncidentNotification } from '../notification.model';
import { aNotification } from '../testing/notification-fixtures';
import { IncidentNotifications } from './incident-notifications';

describe('IncidentNotifications', () => {
  const incidentId = '11111111-1111-4111-8111-111111111111';
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [IncidentNotifications],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function create() {
    const fixture = TestBed.createComponent(IncidentNotifications);
    fixture.componentRef.setInput('incidentId', incidentId);
    fixture.detectChanges();
    return fixture;
  }

  function expectRequest() {
    return http.expectOne(`/api/notifications?incidentId=${incidentId}`);
  }

  async function renderWith(notifications: IncidentNotification[]) {
    const fixture = create();
    expectRequest().flush(notifications);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll<HTMLElement>('[data-testid="notification"]'));

  it('shows each notification with its delivery status', async () => {
    const element = await renderWith([
      aNotification({ channel: 'MOCK', status: 'SENT' }),
      aNotification({ channel: 'SLACK', status: 'PENDING' }),
    ]);

    const [mock, slack] = rows(element);
    expect(mock.textContent).toContain('Mock sink');
    expect(mock.textContent).toContain('Triggered');
    expect(mock.querySelector('[data-testid="delivery-status"]')?.textContent).toBe('Sent');
    expect(slack.querySelector('[data-testid="delivery-status"]')?.textContent).toBe('Sending…');
  });

  it('explains a failed or skipped delivery', async () => {
    const element = await renderWith([
      aNotification({ status: 'FAILED', attempts: 3, detail: 'Slack answered 500' }),
      aNotification({
        eventType: 'ACKNOWLEDGED',
        status: 'SKIPPED',
        attempts: 0,
        detail: 'Slack circuit breaker is open; Slack was not called',
      }),
    ]);

    const [failed, skipped] = rows(element);
    expect(failed.querySelector('.delivery')?.classList).toContain('delivery-failed');
    expect(failed.textContent).toContain('Slack answered 500');
    expect(failed.textContent).toContain('(3 attempts)');
    expect(skipped.querySelector('.delivery')?.classList).toContain('delivery-skipped');
    expect(skipped.textContent).toContain('circuit breaker is open');
    expect(skipped.textContent).not.toContain('attempts');
  });

  it('says so when notification-service cannot be reached', async () => {
    const fixture = create();
    expectRequest().flush(null, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="notifications-unavailable"]')).not.toBeNull();
  });

  it('keeps refreshing, since deliveries finish after the page has loaded', async () => {
    vi.useFakeTimers();
    const fixture = create();
    expectRequest().flush([aNotification({ status: 'PENDING' })]);

    vi.advanceTimersByTime(LIVE_REFRESH_MS);
    expectRequest().flush([aNotification({ status: 'SENT' })]);
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="delivery-status"]')?.textContent).toBe('Sent');
    fixture.destroy();
  });

  it('keeps the last known status when a refresh fails', async () => {
    vi.useFakeTimers();
    const fixture = create();
    expectRequest().flush([aNotification({ status: 'SENT' })]);

    vi.advanceTimersByTime(LIVE_REFRESH_MS);
    expectRequest().flush(null, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(rows(element)).toHaveLength(1);
    expect(element.querySelector('.stale')?.textContent).toContain('last known status');
    fixture.destroy();
  });
});
