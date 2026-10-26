import { HttpErrorResponse } from '@angular/common/http';

import { problemMessage } from './problem-message';

describe('problemMessage', () => {
  it('prefers the problem detail sent by the backend', () => {
    const error = new HttpErrorResponse({
      status: 409,
      error: { title: 'Username taken', detail: "A user named 'riley' already exists" },
    });

    expect(problemMessage(error)).toBe("A user named 'riley' already exists");
  });

  it.each([
    [0, 'Cannot reach the Beacon API'],
    [401, 'Your session has expired'],
    [403, "You don't have permission"],
    [429, 'Too many requests'],
    [500, 'Request failed (500)'],
  ])('explains a bodiless %i response', (status, expected) => {
    expect(problemMessage(new HttpErrorResponse({ status }))).toContain(expected);
  });
});
