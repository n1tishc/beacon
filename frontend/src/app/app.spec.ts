import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';
import { AuthStore } from './core/auth/auth-store';
import { User } from './core/auth/auth.model';

describe('App', () => {
  const user = signal<User | null>(null);
  const logout = vi.fn();

  beforeEach(async () => {
    user.set(null);
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), { provide: AuthStore, useValue: { user, logout } }],
    }).compileComponents();
  });

  async function render() {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows only the brand while signed out', async () => {
    const element = await render();

    expect(element.querySelector('.brand')?.textContent).toContain('Beacon');
    expect(element.querySelector('nav')).toBeNull();
    expect(element.querySelector('.sign-out')).toBeNull();
  });

  it('shows navigation, the signed-in user and a sign-out button', async () => {
    user.set({ id: '1', username: 'riley', displayName: 'Riley Responder', role: 'RESPONDER' });
    const element = await render();

    expect(element.querySelector('nav a')?.textContent).toContain('Incidents');
    expect(element.querySelector('[data-testid="current-user"]')?.textContent).toContain(
      'Riley Responder',
    );
    expect(element.querySelector('.account-role')?.textContent).toContain('Responder');

    element.querySelector<HTMLButtonElement>('.sign-out')!.click();
    expect(logout).toHaveBeenCalled();
  });
});
