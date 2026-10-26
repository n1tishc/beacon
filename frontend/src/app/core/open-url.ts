import { DOCUMENT } from '@angular/common';
import { InjectionToken, inject } from '@angular/core';

/**
 * Sends the browser to a URL, e.g. a presigned download that S3 serves as a file to save. A token
 * rather than a direct `location.assign`, so specs can see where the page would have gone.
 */
export const OPEN_URL = new InjectionToken<(url: string) => void>('OPEN_URL', {
  providedIn: 'root',
  factory: () => {
    const document = inject(DOCUMENT);
    return (url: string) => document.location.assign(url);
  },
});
