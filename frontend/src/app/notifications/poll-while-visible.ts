import { DestroyRef, Signal, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Observable, catchError, exhaustMap, startWith, tap } from 'rxjs';

import { whileVisible } from '../core/while-visible';

export interface Polled<T> {
  /** The latest response; null until the first one arrives. */
  value: Signal<T | null>;
  /** The latest request failed. The last value, if any, is kept. */
  failing: Signal<boolean>;
}

/**
 * Sends `request` now, then again on every visible-tab tick, until `destroyRef` is destroyed.
 * A request still in flight is never stacked on (`exhaustMap`).
 */
export function pollWhileVisible<T>(
  document: Document,
  destroyRef: DestroyRef,
  request: () => Observable<T>,
): Polled<T> {
  const value = signal<T | null>(null);
  const failing = signal(false);
  whileVisible(document)
    .pipe(
      startWith(null),
      exhaustMap(() =>
        request().pipe(
          tap((response) => {
            value.set(response);
            failing.set(false);
          }),
          catchError(() => {
            failing.set(true);
            return EMPTY;
          }),
        ),
      ),
      takeUntilDestroyed(destroyRef),
    )
    .subscribe();
  return { value: value.asReadonly(), failing: failing.asReadonly() };
}
