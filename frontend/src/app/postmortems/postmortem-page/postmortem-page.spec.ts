import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthStore } from '../../core/auth/auth-store';
import { Role } from '../../core/auth/auth.model';
import { OPEN_URL } from '../../core/open-url';
import { Incident } from '../../incidents/incident.model';
import { anIncident } from '../../incidents/testing/incident-fixtures';
import { Attachment, Postmortem } from '../postmortem.model';
import { aPostmortem, anAttachment } from '../testing/postmortem-fixtures';
import { PostmortemPage } from './postmortem-page';

const BASE = '/api/incidents/abc/postmortem';

describe('PostmortemPage', () => {
  let http: HttpTestingController;
  let role: Role;
  let opened: string[];

  beforeEach(async () => {
    role = 'RESPONDER';
    opened = [];
    const rank: Record<Role, number> = { VIEWER: 0, RESPONDER: 1, ADMIN: 2 };
    await TestBed.configureTestingModule({
      imports: [PostmortemPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: OPEN_URL, useValue: (url: string) => opened.push(url) },
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

  async function render(
    postmortem: Postmortem | null,
    attachments: Attachment[] = [],
    incident: Incident = anIncident({ id: 'abc', status: 'RESOLVED' }),
  ) {
    const fixture = TestBed.createComponent(PostmortemPage);
    fixture.componentRef.setInput('id', 'abc');
    fixture.detectChanges();
    http.expectOne('/api/incidents/abc').flush(incident);
    const get = http.expectOne(BASE);
    if (postmortem) {
      get.flush(postmortem);
    } else {
      get.flush({ title: 'Postmortem not found' }, { status: 404, statusText: 'Not Found' });
    }
    http.expectOne(`${BASE}/attachments`).flush(attachments);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  const testId = (element: HTMLElement, id: string) =>
    element.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  function type(element: HTMLElement, id: string, value: string) {
    const textarea = element.querySelector<HTMLTextAreaElement>(`#${id}`)!;
    textarea.value = value;
    textarea.dispatchEvent(new Event('input'));
  }

  it('starts an empty form for the first draft and saves it without a version', async () => {
    const { fixture, element } = await render(null);
    expect(testId(element, 'save')?.textContent).toContain('Save postmortem');

    type(element, 'summary', '  Checkout was down for 9 minutes.  ');
    type(element, 'rootCause', 'A memory leak in 4.2.0');
    testId(element, 'save')!.click();

    const save = http.expectOne(BASE);
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({
      version: null,
      summary: 'Checkout was down for 9 minutes.',
      impact: null,
      rootCause: 'A memory leak in 4.2.0',
      actionItems: null,
    });
    save.flush(aPostmortem({ rootCause: 'A memory leak in 4.2.0' }));
    await fixture.whenStable();

    expect(testId(element, 'last-saved')?.textContent).toContain('Last saved by riley');
    expect(testId(element, 'save')?.textContent).toContain('Save changes');
  });

  it('requires a summary before saving', async () => {
    const { fixture, element } = await render(null);

    testId(element, 'save')!.click();
    await fixture.whenStable();

    expect(element.querySelector('.field-error')?.textContent).toContain('Write a summary');
    http.expectNone(BASE);
  });

  it('keeps the draft when someone else saved first, and can switch to their version', async () => {
    const { fixture, element } = await render(aPostmortem({ version: 0, summary: 'Original' }));
    type(element, 'summary', 'My rewrite');
    testId(element, 'save')!.click();

    http
      .expectOne(BASE)
      .flush(
        { title: 'Postmortem changed', detail: 'saved by someone else' },
        { status: 409, statusText: 'Conflict' },
      );
    http.expectOne(BASE).flush(aPostmortem({ version: 1, summary: 'Theirs', updatedBy: 'avery' }));
    await fixture.whenStable();

    const summary = element.querySelector<HTMLTextAreaElement>('#summary')!;
    expect(testId(element, 'notice')?.textContent).toContain('avery saved this postmortem');
    expect(summary.value).toBe('My rewrite');

    testId(element, 'discard')!.click();
    await fixture.whenStable();

    expect(summary.value).toBe('Theirs');
    expect(testId(element, 'notice')).toBeNull();
  });

  it('shows viewers the postmortem read-only, with only the sections that were written', async () => {
    role = 'VIEWER';
    const { element } = await render(
      aPostmortem({ summary: 'Down 9 min', actionItems: 'Heap alert' }),
    );

    expect(element.querySelector('textarea')).toBeNull();
    expect(testId(element, 'file-input')).toBeNull();
    const terms = Array.from(element.querySelectorAll('dt')).map((dt) => dt.textContent?.trim());
    expect(terms).toEqual(['Summary', 'Action items']);
  });

  it('tells viewers when nobody has written it yet', async () => {
    role = 'VIEWER';
    const { element } = await render(null);

    expect(testId(element, 'no-postmortem')).not.toBeNull();
  });

  it('waits for the incident to be resolved', async () => {
    const { element } = await render(null, [], anIncident({ id: 'abc', status: 'ACKNOWLEDGED' }));

    expect(testId(element, 'not-resolved')).not.toBeNull();
    expect(element.querySelector('form')).toBeNull();
  });

  it('downloads through a freshly signed URL', async () => {
    const attachment = anAttachment({ fileName: 'heap.png' });
    const { element } = await render(aPostmortem(), [attachment]);

    element.querySelector<HTMLButtonElement>('[aria-label="Download heap.png"]')!.click();
    http
      .expectOne(`${BASE}/attachments/${attachment.id}/download`)
      .flush({ url: 'http://localhost:4566/signed', expiresAt: '' });

    expect(opened).toEqual(['http://localhost:4566/signed']);
  });

  it('uploads a picked file and lists it once the server confirms it', async () => {
    const { fixture, element } = await render(aPostmortem());
    const input = testId(element, 'file-input') as HTMLInputElement;
    const file = new File(['12:01 OOMKilled'], 'checkout.log');
    Object.defineProperty(input, 'files', { value: [file] });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(testId(element, 'uploading')?.textContent).toContain('Uploading checkout.log');
    const pending = anAttachment({ fileName: 'checkout.log', status: 'PENDING' });
    http.expectOne(`${BASE}/attachments`).flush({
      attachment: pending,
      uploadUrl: 'http://localhost:4566/bucket/key?X-Amz-Signature=sig',
      uploadHeaders: { 'content-type': 'text/plain' },
      expiresAt: '',
    });
    const put = http.expectOne('http://localhost:4566/bucket/key?X-Amz-Signature=sig');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toBe(file);
    expect(put.request.headers.get('content-type')).toBe('text/plain');
    put.flush('');
    http
      .expectOne(`${BASE}/attachments/${pending.id}/complete`)
      .flush({ ...pending, status: 'UPLOADED' });
    http.expectOne(`${BASE}/attachments`).flush([{ ...pending, status: 'UPLOADED' }]);
    await fixture.whenStable();

    expect(testId(element, 'attachments')?.textContent).toContain('checkout.log');
    expect(testId(element, 'uploading')).toBeNull();
  });
});
