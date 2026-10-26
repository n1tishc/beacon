import {
  EMPTY,
  Observable,
  distinctUntilChanged,
  fromEvent,
  map,
  startWith,
  switchMap,
  timer,
} from 'rxjs';

/** How often pages poll while they are visible. */
export const LIVE_REFRESH_MS = 5000;

/**
 * Ticks every `periodMs`, but only while the browser tab is visible, and once straight away when the
 * user comes back to it. The first tick comes one period after subscribing: callers have just loaded.
 */
export function whileVisible(document: Document, periodMs = LIVE_REFRESH_MS): Observable<unknown> {
  const visible$ = fromEvent(document, 'visibilitychange').pipe(
    map(() => !document.hidden),
    startWith(!document.hidden),
    distinctUntilChanged(),
  );
  return visible$.pipe(
    switchMap((visible, index) => (visible ? timer(index === 0 ? periodMs : 0, periodMs) : EMPTY)),
  );
}
