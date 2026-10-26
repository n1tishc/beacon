import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import {
  EMPTY,
  Observable,
  catchError,
  defer,
  finalize,
  forkJoin,
  map,
  of,
  switchMap,
  tap,
} from 'rxjs';

import { problemMessage, problemTitle } from '../core/problem-message';
import { IncidentApi } from '../incidents/incident-api';
import { Incident } from '../incidents/incident.model';
import { PostmortemApi } from './postmortem-api';
import {
  Attachment,
  Postmortem,
  PostmortemContent,
  attachmentProblem,
  contentTypeOf,
} from './postmortem.model';

interface PostmortemState {
  incident: Incident | null;
  /** Null until someone writes the first draft. */
  postmortem: Postmortem | null;
  attachments: Attachment[];
  loading: boolean;
  saving: boolean;
  /** The name of the file being uploaded, if any. */
  uploading: string | null;
  error: string | null;
  attachmentError: string | null;
  /** Shown after a save lost to someone else's. */
  notice: string | null;
}

const initialState: PostmortemState = {
  incident: null,
  postmortem: null,
  attachments: [],
  loading: false,
  saving: false,
  uploading: null,
  error: null,
  attachmentError: null,
  notice: null,
};

/** The backend's problem title when a save was based on an outdated version (ApiExceptionHandler). */
const POSTMORTEM_CHANGED = 'Postmortem changed';

/**
 * State for one incident's postmortem page. Provided by the page, like IncidentDetailStore.
 *
 * Saving works like an incident command (the version on screen goes with it), with one difference:
 * when someone else saved first, the user's draft is kept rather than replaced, because it may be a
 * lot of writing. The store loads the other version, so saving again replaces it knowingly.
 */
@Injectable()
export class PostmortemStore {
  private readonly api = inject(PostmortemApi);
  private readonly incidents = inject(IncidentApi);
  private readonly state = signal<PostmortemState>(initialState);

  readonly incident = computed(() => this.state().incident);
  readonly postmortem = computed(() => this.state().postmortem);
  readonly attachments = computed(() => this.state().attachments);
  readonly loading = computed(() => this.state().loading);
  readonly saving = computed(() => this.state().saving);
  readonly uploading = computed(() => this.state().uploading);
  readonly error = computed(() => this.state().error);
  readonly attachmentError = computed(() => this.state().attachmentError);
  readonly notice = computed(() => this.state().notice);

  /** Emits once everything is loaded, so the page can fill its form; emits nothing on failure. */
  load(incidentId: string): Observable<Postmortem | null> {
    return defer(() => {
      this.patch({ loading: true, error: null });
      return forkJoin({
        incident: this.incidents.get(incidentId),
        postmortem: this.fetchPostmortem(incidentId),
        attachments: this.api.attachments(incidentId),
      }).pipe(
        tap((loaded) => this.patch(loaded)),
        map(({ postmortem }) => postmortem),
        catchError((error) => {
          this.patch({ error: problemMessage(error) });
          return EMPTY;
        }),
        finalize(() => this.patch({ loading: false })),
      );
    });
  }

  /** Emits true once saved, false if the save was refused (see `notice` and `error`). */
  save(content: PostmortemContent): Observable<boolean> {
    return defer(() => {
      const incident = this.incident();
      if (!incident || this.saving()) {
        return of(false);
      }
      this.patch({ saving: true, error: null, notice: null });
      const version = this.postmortem()?.version ?? null;
      return this.api.save(incident.id, { version, ...content }).pipe(
        tap((postmortem) => this.patch({ postmortem })),
        map(() => true),
        catchError((error) => this.handleSaveError(incident.id, error)),
        finalize(() => this.patch({ saving: false })),
      );
    });
  }

  /** Forgets the notice once the user has chosen to load the other version instead of theirs. */
  dismissNotice(): void {
    this.patch({ notice: null });
  }

  /**
   * Announce, upload straight to S3, then confirm. The attachment is listed only once the server
   * has checked what arrived. Emits true once listed.
   */
  upload(file: File): Observable<boolean> {
    return defer(() => {
      const incident = this.incident();
      if (!incident || this.uploading()) {
        return of(false);
      }
      const problem = attachmentProblem(file);
      if (problem) {
        this.patch({ attachmentError: problem });
        return of(false);
      }
      this.patch({ uploading: file.name, attachmentError: null });
      return this.api.announce(incident.id, file.name, contentTypeOf(file), file.size).pipe(
        switchMap((ticket) =>
          this.api.upload(ticket, file).pipe(
            catchError((error) => {
              throw new UploadFailed(file.name, error);
            }),
            switchMap(() => this.api.complete(incident.id, ticket.attachment.id)),
          ),
        ),
        switchMap(() => this.api.attachments(incident.id)),
        tap((attachments) => this.patch({ attachments })),
        map(() => true),
        catchError((error) => {
          this.patch({
            attachmentError: error instanceof UploadFailed ? error.message : problemMessage(error),
          });
          return of(false);
        }),
        finalize(() => this.patch({ uploading: null })),
      );
    });
  }

  /** A fresh, short-lived download URL, or null if none could be had (see `attachmentError`). */
  downloadUrl(attachment: Attachment): Observable<string | null> {
    return defer(() => {
      const incident = this.incident();
      if (!incident) {
        return of(null);
      }
      this.patch({ attachmentError: null });
      return this.api.downloadLink(incident.id, attachment.id).pipe(
        map((link) => link.url),
        catchError((error) => {
          this.patch({ attachmentError: problemMessage(error) });
          return of(null);
        }),
      );
    });
  }

  private handleSaveError(incidentId: string, error: unknown): Observable<boolean> {
    if (problemTitle(error) !== POSTMORTEM_CHANGED) {
      this.patch({ error: problemMessage(error) });
      return of(false);
    }
    return this.fetchPostmortem(incidentId).pipe(
      tap((postmortem) =>
        this.patch({
          postmortem,
          notice:
            `${postmortem?.updatedBy ?? 'Someone else'} saved this postmortem while you were editing. ` +
            'Your text is still in the form: save again to replace their version, ' +
            'or discard yours to read theirs.',
        }),
      ),
      map(() => false),
      catchError((reloadError) => {
        this.patch({ error: problemMessage(reloadError) });
        return of(false);
      }),
    );
  }

  /** A 404 here just means nobody has written it yet. */
  private fetchPostmortem(incidentId: string): Observable<Postmortem | null> {
    return this.api.get(incidentId).pipe(
      catchError((error: unknown) => {
        if (
          error instanceof HttpErrorResponse &&
          error.status === 404 &&
          problemTitle(error) === 'Postmortem not found'
        ) {
          return of(null);
        }
        throw error;
      }),
    );
  }

  private patch(partial: Partial<PostmortemState>): void {
    this.state.update((state) => ({ ...state, ...partial }));
  }
}

/** The PUT to S3 itself failed: S3 answers in XML, not problem JSON, so it gets its own message. */
class UploadFailed extends Error {
  constructor(fileName: string, cause: unknown) {
    super(`Couldn't upload ${fileName} to file storage. Try again.`, { cause });
  }
}
