import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';

import { IncidentApi } from '../incidents/incident-api';
import { anIncident } from '../incidents/testing/incident-fixtures';
import { PostmortemApi } from './postmortem-api';
import { PostmortemStore } from './postmortem-store';
import { Postmortem } from './postmortem.model';
import { aPostmortem, anAttachment, anUploadTicket } from './testing/postmortem-fixtures';

function problem(status: number, title: string): Observable<never> {
  return throwError(
    () => new HttpErrorResponse({ status, error: { title, detail: `${title} (detail)` } }),
  );
}

const CONTENT = { summary: 'Mine', impact: null, rootCause: null, actionItems: null };

describe('PostmortemStore', () => {
  let store: PostmortemStore;
  let api: Record<
    'get' | 'save' | 'attachments' | 'announce' | 'upload' | 'complete' | 'downloadLink',
    ReturnType<typeof vi.fn>
  >;
  let current: Postmortem | null;

  function load() {
    let loaded: Postmortem | null | undefined;
    store.load('abc').subscribe((postmortem) => (loaded = postmortem));
    return loaded;
  }

  beforeEach(() => {
    current = null;
    api = {
      get: vi.fn(() => (current ? of(current) : problem(404, 'Postmortem not found'))),
      save: vi.fn(),
      attachments: vi.fn(() => of([])),
      announce: vi.fn(),
      upload: vi.fn(() => of('')),
      complete: vi.fn(),
      downloadLink: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        PostmortemStore,
        { provide: PostmortemApi, useValue: api },
        {
          provide: IncidentApi,
          useValue: { get: () => of(anIncident({ id: 'abc', status: 'RESOLVED' })) },
        },
      ],
    });
    store = TestBed.inject(PostmortemStore);
  });

  it('treats a missing postmortem as "not written yet", not as an error', () => {
    expect(load()).toBeNull();
    expect(store.postmortem()).toBeNull();
    expect(store.error()).toBeNull();
    expect(store.incident()?.id).toBe('abc');
  });

  it('reports other load failures', () => {
    api.get.mockReturnValue(problem(500, 'Internal Server Error'));

    expect(load()).toBeUndefined();
    expect(store.error()).toBe('Internal Server Error (detail)');
  });

  it('saves the first draft without a version, and later ones with the version on screen', () => {
    load();
    api.save.mockReturnValueOnce(of(aPostmortem({ version: 0 })));
    store.save(CONTENT).subscribe();
    expect(api.save).toHaveBeenLastCalledWith('abc', { version: null, ...CONTENT });

    api.save.mockReturnValueOnce(of(aPostmortem({ version: 1 })));
    store.save(CONTENT).subscribe();
    expect(api.save).toHaveBeenLastCalledWith('abc', { version: 0, ...CONTENT });
    expect(store.postmortem()?.version).toBe(1);
  });

  it('on a conflict, loads the other version and says who saved it, keeping the draft for the page', () => {
    load();
    current = aPostmortem({ version: 3, updatedBy: 'avery', summary: 'Theirs' });
    api.save.mockReturnValue(problem(409, 'Postmortem changed'));

    let saved: boolean | undefined;
    store.save(CONTENT).subscribe((result) => (saved = result));

    expect(saved).toBe(false);
    expect(store.postmortem()?.version).toBe(3); // so saving again replaces theirs knowingly
    expect(store.notice()).toContain('avery saved this postmortem while you were editing');
    expect(store.error()).toBeNull();
    expect(store.saving()).toBe(false);
  });

  it('uploads by announcing, PUTting to S3, then confirming, and lists the file only after that', () => {
    load();
    const attachment = anAttachment({ status: 'PENDING' });
    const ticket = anUploadTicket(attachment);
    api.announce.mockReturnValue(of(ticket));
    api.complete.mockReturnValue(of({ ...attachment, status: 'UPLOADED' }));
    api.attachments.mockReturnValue(of([{ ...attachment, status: 'UPLOADED' }]));
    const file = new File(['12:01 OOMKilled'], 'checkout.log'); // no type: the browser doesn't know .log

    let done: boolean | undefined;
    store.upload(file).subscribe((result) => (done = result));

    expect(api.announce).toHaveBeenCalledWith('abc', 'checkout.log', 'text/plain', file.size);
    expect(api.upload).toHaveBeenCalledWith(ticket, file);
    expect(api.complete).toHaveBeenCalledWith('abc', attachment.id);
    expect(done).toBe(true);
    expect(store.attachments()).toHaveLength(1);
    expect(store.uploading()).toBeNull();
  });

  it('refuses files the server would refuse, without calling it', () => {
    load();

    store.upload(new File(['<html>'], 'page.html', { type: 'text/html' })).subscribe();
    expect(store.attachmentError()).toContain("page.html can't be attached");

    const big = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'heap.png', { type: 'image/png' });
    store.upload(big).subscribe();
    expect(store.attachmentError()).toBe('heap.png is larger than 10 MB.');

    expect(api.announce).not.toHaveBeenCalled();
  });

  it('explains a failed upload to S3 in its own words, and does not confirm it', () => {
    load();
    api.announce.mockReturnValue(of(anUploadTicket(anAttachment({ status: 'PENDING' }))));
    api.upload.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 403, error: '<Error/>' })),
    );

    store.upload(new File(['x'], 'graph.png', { type: 'image/png' })).subscribe();

    expect(store.attachmentError()).toBe("Couldn't upload graph.png to file storage. Try again.");
    expect(api.complete).not.toHaveBeenCalled();
    expect(store.uploading()).toBeNull();
  });

  it('shows the server explanation when it refuses an attachment', () => {
    load();
    api.announce.mockReturnValue(problem(503, 'File storage unavailable'));

    store.upload(new File(['x'], 'graph.png', { type: 'image/png' })).subscribe();

    expect(store.attachmentError()).toBe('File storage unavailable (detail)');
  });

  it('fetches a fresh download URL for each download', () => {
    load();
    api.downloadLink.mockReturnValue(of({ url: 'http://s3.test/signed', expiresAt: '' }));
    const attachment = anAttachment();

    let url: string | null | undefined;
    store.downloadUrl(attachment).subscribe((result) => (url = result));

    expect(api.downloadLink).toHaveBeenCalledWith('abc', attachment.id);
    expect(url).toBe('http://s3.test/signed');
  });
});
