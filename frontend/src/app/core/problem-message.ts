import { HttpErrorResponse } from '@angular/common/http';

/**
 * Turns an HTTP error into a sentence a user can read.
 * The backend answers with RFC 7807 problem details, so prefer its `detail`, then `title`.
 */
export function problemMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'Cannot reach the Beacon API. Check that the backend is running.';
    }
    const problem = error.error as { detail?: string; title?: string } | null;
    return problem?.detail ?? problem?.title ?? `Request failed (${error.status})`;
  }
  return 'Something went wrong.';
}
