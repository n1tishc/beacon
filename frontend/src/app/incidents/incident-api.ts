import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CreateIncidentRequest, Incident, Page, TimelineEntry } from './incident.model';

/** Thin HTTP client for incident-service. No state lives here; see IncidentStore and IncidentDetailStore. */
@Injectable({ providedIn: 'root' })
export class IncidentApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/incidents';

  list(page = 0, size = 50): Observable<Page<Incident>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<Incident>>(this.baseUrl, { params });
  }

  get(id: string): Observable<Incident> {
    return this.http.get<Incident>(`${this.baseUrl}/${id}`);
  }

  create(request: CreateIncidentRequest): Observable<Incident> {
    return this.http.post<Incident>(this.baseUrl, request);
  }

  acknowledge(incident: Incident): Observable<Incident> {
    return this.http.post<Incident>(`${this.baseUrl}/${incident.id}/acknowledge`, {
      version: incident.version,
    });
  }

  resolve(incident: Incident, note: string | null): Observable<Incident> {
    return this.http.post<Incident>(`${this.baseUrl}/${incident.id}/resolve`, {
      version: incident.version,
      note,
    });
  }

  timeline(id: string): Observable<TimelineEntry[]> {
    return this.http.get<TimelineEntry[]>(`${this.baseUrl}/${id}/timeline`);
  }

  addNote(id: string, text: string): Observable<TimelineEntry> {
    return this.http.post<TimelineEntry>(`${this.baseUrl}/${id}/notes`, { text });
  }
}
