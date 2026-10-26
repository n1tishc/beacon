import { HttpErrorResponse } from '@angular/common/http';

/**
 * Turns an HTTP error into a sentence a user can read.
 * The backend answers with RFC 7807 problem details, so prefer its `detail`, then `title`.
 * Some responses (e.g. Spring Security's 401/403, the gateway's 429) have no body; those get a
 * message based on the status alone.
 */
export function problemMessage(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return 'Something went wrong.';
  }
  if (error.status === 0) {
    return 'Cannot reach the Beacon API. Check that the backend is running.';
  }
  const problem = error.error as { detail?: string; title?: string } | null;
  const fromServer = problem?.detail ?? problem?.title;
  if (fromServer) {
    return fromServer;
  }
  switch (error.status) {
    case 401:
      return 'Your session has expired. Please sign in again.';
    case 403:
      return "You don't have permission to do that.";
    case 429:
      return 'Too many requests. Wait a moment and try again.';
    default:
      return `Request failed (${error.status})`;
  }
}
