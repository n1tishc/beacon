import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AuthStore } from '../core/auth/auth-store';
import { aUser } from '../core/auth/testing/auth-fixtures';
import { LoginPage, safeReturnUrl } from './login-page';

describe('LoginPage', () => {
  let login: ReturnType<typeof vi.fn>;
  let router: Router;

  beforeEach(async () => {
    login = vi.fn(() => of(aUser()));
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideRouter([]), { provide: AuthStore, useValue: { login } }],
    }).compileComponents();
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  async function render(returnUrl?: string) {
    const fixture = TestBed.createComponent(LoginPage);
    if (returnUrl) {
      fixture.componentRef.setInput('returnUrl', returnUrl);
    }
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    return { fixture, element };
  }

  function fill(element: HTMLElement, username: string, password: string) {
    for (const [selector, value] of [
      ['#login-username', username],
      ['#login-password', password],
    ]) {
      const input = element.querySelector<HTMLInputElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
  }

  it('signs in and goes to the page the user originally asked for', async () => {
    const { fixture, element } = await render('/incidents?page=2');
    fill(element, ' riley ', 'secret');

    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(login).toHaveBeenCalledWith('riley', 'secret');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/incidents?page=2');
  });

  it('shows the server message and clears the password when sign-in fails', async () => {
    login.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 401,
            error: { detail: 'Invalid username or password.' },
          }),
      ),
    );
    const { fixture, element } = await render();
    fill(element, 'riley', 'wrong');

    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(element.querySelector('[role="alert"]')?.textContent).toContain(
      'Invalid username or password.',
    );
    expect(element.querySelector<HTMLInputElement>('#login-password')!.value).toBe('');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('does not submit an empty form', async () => {
    const { fixture, element } = await render();

    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(login).not.toHaveBeenCalled();
    expect(element.querySelector('.field-error')).not.toBeNull();
  });
});

describe('safeReturnUrl', () => {
  it.each([
    [undefined, '/incidents'],
    ['/incidents/42', '/incidents/42'],
    ['//evil.example', '/incidents'],
    ['/\\evil.example', '/incidents'],
    ['https://evil.example', '/incidents'],
    ['/login', '/incidents'],
  ])('%s -> %s', (input, expected) => {
    expect(safeReturnUrl(input)).toBe(expected);
  });
});
