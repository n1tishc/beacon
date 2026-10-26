import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';

import { AuthStore } from '../core/auth/auth-store';
import { problemMessage } from '../core/problem-message';

/**
 * Only in-app paths are allowed as a post-login destination, so a crafted link like
 * /login?returnUrl=//evil.example cannot bounce the user off-site.
 */
export function safeReturnUrl(url: string | undefined): string {
  const isInternalPath =
    !!url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/\\');
  return isInternalPath && !url.startsWith('/login') ? url : '/incidents';
}

@Component({
  selector: 'app-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  templateUrl: './login-page.html',
  styleUrl: './login-page.scss',
})
export class LoginPage {
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);

  /** Bound from the ?returnUrl= query parameter (withComponentInputBinding). */
  readonly returnUrl = input<string>();

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    username: ['', [Validators.required]],
    password: ['', [Validators.required]],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { username, password } = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.auth
      .login(username.trim(), password)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => void this.router.navigateByUrl(safeReturnUrl(this.returnUrl())),
        error: (error: unknown) => {
          this.error.set(problemMessage(error));
          this.form.controls.password.reset();
        },
      });
  }
}
