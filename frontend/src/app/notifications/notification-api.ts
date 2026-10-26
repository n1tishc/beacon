import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ChannelStatus, IncidentNotification } from './notification.model';

/** Thin HTTP client for notification-service's read-only API. */
@Injectable({ providedIn: 'root' })
export class NotificationApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/notifications';

  forIncident(incidentId: string): Observable<IncidentNotification[]> {
    const params = new HttpParams().set('incidentId', incidentId);
    return this.http.get<IncidentNotification[]>(this.baseUrl, { params });
  }

  channels(): Observable<ChannelStatus[]> {
    return this.http.get<ChannelStatus[]>(`${this.baseUrl}/channels`);
  }
}
