import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { IncidentApi } from './incident-api';

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
});
