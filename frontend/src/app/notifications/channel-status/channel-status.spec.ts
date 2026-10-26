import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ChannelStatus } from '../notification.model';
import { aChannel } from '../testing/notification-fixtures';
import { ChannelStatusBar } from './channel-status';

describe('ChannelStatusBar', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChannelStatusBar],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function renderWith(channels: ChannelStatus[]) {
    const fixture = TestBed.createComponent(ChannelStatusBar);
    fixture.detectChanges();
    http.expectOne('/api/notifications/channels').flush(channels);
    await fixture.whenStable();
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
        '[data-testid="channel"]',
      ),
    );
  }

  it('shows healthy channels as OK', async () => {
    const [mock, slack] = await renderWith([
      aChannel({ channel: 'MOCK', circuitState: null }),
      aChannel({ channel: 'SLACK', circuitState: 'CLOSED' }),
    ]);

    expect(mock.textContent?.trim()).toBe('Mock sink: OK');
    expect(slack.textContent?.trim()).toBe('Slack: OK');
    expect(slack.classList).not.toContain('degraded');
  });

  it('flags an open or recovering Slack circuit', async () => {
    const [open] = await renderWith([aChannel({ circuitState: 'OPEN' })]);

    expect(open.textContent).toContain('Slack: Circuit open, calls paused');
    expect(open.classList).toContain('degraded');
  });

  it('says when Slack is not configured', async () => {
    const [slack] = await renderWith([aChannel({ enabled: false, circuitState: null })]);

    expect(slack.textContent).toContain('Slack: Not configured');
    expect(slack.classList).toContain('off');
  });

  it('shows nothing if notification-service cannot be reached', async () => {
    const fixture = TestBed.createComponent(ChannelStatusBar);
    fixture.detectChanges();
    http
      .expectOne('/api/notifications/channels')
      .flush(null, { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('ul')).toBeNull();
  });
});
