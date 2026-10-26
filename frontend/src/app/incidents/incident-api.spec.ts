import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { IncidentApi } from './incident-api';
import { anIncident } from './testing/incident-fixtures';

describe('IncidentApi', () => {
  let api: IncidentApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(IncidentApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests a page of incidents', () => {
    api.list(2, 25).subscribe();

    const request = http.expectOne('/api/incidents?page=2&size=25');
    expect(request.request.method).toBe('GET');
    request.flush({ content: [], page: 2, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('posts a new incident', () => {
    const body = { title: 'Disk full', description: null, severity: 'LOW' as const };

    api.create(body).subscribe();

    const request = http.expectOne('/api/incidents');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    request.flush({});
  });

  it('sends the version it last saw with acknowledge and resolve', () => {
    const incident = anIncident({ id: 'abc', version: 3 });

    api.acknowledge(incident).subscribe();
    api.resolve(incident, 'Rolled back').subscribe();

    const acknowledge = http.expectOne('/api/incidents/abc/acknowledge');
    expect(acknowledge.request.body).toEqual({ version: 3 });
    acknowledge.flush({});
    const resolve = http.expectOne('/api/incidents/abc/resolve');
    expect(resolve.request.body).toEqual({ version: 3, note: 'Rolled back' });
    resolve.flush({});
  });

  it('reads the timeline and posts notes', () => {
    api.timeline('abc').subscribe();
    api.addNote('abc', 'Paged the DB team').subscribe();

    http.expectOne({ method: 'GET', url: '/api/incidents/abc/timeline' }).flush([]);
    const note = http.expectOne({ method: 'POST', url: '/api/incidents/abc/notes' });
    expect(note.request.body).toEqual({ text: 'Paged the DB team' });
    note.flush({});
  });
});
