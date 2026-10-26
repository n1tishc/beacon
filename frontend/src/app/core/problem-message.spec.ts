import { HttpErrorResponse } from '@angular/common/http';

import { fieldErrors, problemMessage, problemTitle } from './problem-message';

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

describe('problemTitle', () => {
  it('returns the problem title, or null without a problem body', () => {
    expect(
      problemTitle(new HttpErrorResponse({ status: 409, error: { title: 'Incident changed' } })),
    ).toBe('Incident changed');
    expect(problemTitle(new HttpErrorResponse({ status: 403 }))).toBeNull();
  });
});

describe('fieldErrors', () => {
  it('maps validation errors by field', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: {
        title: 'Invalid request',
        errors: [
          { field: 'title', message: 'must not be blank' },
          { field: 'severity', message: 'must not be null' },
        ],
      },
    });

    expect(fieldErrors(error)).toEqual({
      title: 'must not be blank',
      severity: 'must not be null',
    });
  });

  it('is empty for any other error', () => {
    expect(fieldErrors(new HttpErrorResponse({ status: 500 }))).toEqual({});
    expect(fieldErrors(new Error('boom'))).toEqual({});
  });
});
