import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  Attachment,
  DownloadLink,
  Postmortem,
  SavePostmortemRequest,
  UploadTicket,
} from './postmortem.model';

/** Thin HTTP client for an incident's postmortem and attachments. State lives in PostmortemStore. */
@Injectable({ providedIn: 'root' })
export class PostmortemApi {
  private readonly http = inject(HttpClient);

  /** 404 "Postmortem not found" until someone writes one. */
  get(incidentId: string): Observable<Postmortem> {
    return this.http.get<Postmortem>(this.url(incidentId));
  }

  save(incidentId: string, request: SavePostmortemRequest): Observable<Postmortem> {
    return this.http.put<Postmortem>(this.url(incidentId), request);
  }

  attachments(incidentId: string): Observable<Attachment[]> {
    return this.http.get<Attachment[]>(`${this.url(incidentId)}/attachments`);
  }

  announce(incidentId: string, fileName: string, contentType: string, sizeBytes: number) {
    return this.http.post<UploadTicket>(`${this.url(incidentId)}/attachments`, {
      fileName,
      contentType,
      sizeBytes,
    });
  }

  /**
   * The file goes straight to S3, not through Beacon. The URL is absolute, so the auth interceptor
   * leaves it alone: S3 authenticates the request by the URL's signature, and would reject a bearer token.
   */
  upload(ticket: UploadTicket, file: File): Observable<unknown> {
    return this.http.put(ticket.uploadUrl, file, {
      headers: new HttpHeaders(ticket.uploadHeaders),
      responseType: 'text',
    });
  }

  complete(incidentId: string, attachmentId: string): Observable<Attachment> {
    return this.http.post<Attachment>(
      `${this.url(incidentId)}/attachments/${attachmentId}/complete`,
      null,
    );
  }

  downloadLink(incidentId: string, attachmentId: string): Observable<DownloadLink> {
    return this.http.get<DownloadLink>(
      `${this.url(incidentId)}/attachments/${attachmentId}/download`,
    );
  }

  private url(incidentId: string): string {
    return `/api/incidents/${incidentId}/postmortem`;
  }
}
