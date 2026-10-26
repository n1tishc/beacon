import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CreateIncidentRequest, Incident, Page } from './incident.model';

/** Thin HTTP client for incident-service. No state lives here; see IncidentStore. */
@Injectable({ providedIn: 'root' })
export class IncidentApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/incidents';

  list(page = 0, size = 50): Observable<Page<Incident>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<Incident>>(this.baseUrl, { params });
  }

  create(request: CreateIncidentRequest): Observable<Incident> {
    return this.http.post<Incident>(this.baseUrl, request);
  }
}
