import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { IncidentStore } from '../incident-store';
import { anIncident } from '../testing/incident-fixtures';
import { CreateIncidentForm } from './create-incident-form';

describe('CreateIncidentForm', () => {
  let store: {
    create: ReturnType<typeof vi.fn>;
    saving: () => boolean;
    fieldErrors: WritableSignal<Record<string, string>>;
  };

  beforeEach(async () => {
    store = { create: vi.fn(() => of(anIncident())), saving: () => false, fieldErrors: signal({}) };
    await TestBed.configureTestingModule({
      imports: [CreateIncidentForm],
      providers: [{ provide: IncidentStore, useValue: store }],
    }).compileComponents();
  });

  async function render() {
    const fixture = TestBed.createComponent(CreateIncidentForm);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  function type(element: HTMLElement, selector: string, value: string) {
    const input = element.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('does not submit a whitespace-only title and shows the validation message', async () => {
    const { fixture, element } = await render();
    type(element, '#incident-title', '   ');

    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(store.create).not.toHaveBeenCalled();
    expect(element.querySelector('.field-error')?.textContent).toContain(
      'Give the incident a title',
    );
  });

  it('submits trimmed values and resets the form on success', async () => {
    const { fixture, element } = await render();
    type(element, '#incident-title', '  Payments down  ');
    type(element, '#incident-description', '   ');
    const severity = element.querySelector<HTMLSelectElement>('#incident-severity')!;
    severity.value = 'CRITICAL';
    severity.dispatchEvent(new Event('change'));

    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(store.create).toHaveBeenCalledWith({
      title: 'Payments down',
      description: null,
      severity: 'CRITICAL',
    });
    expect(element.querySelector<HTMLInputElement>('#incident-title')!.value).toBe('');
  });

  it('shows validation errors the server sent next to their fields', async () => {
    const { fixture, element } = await render();

    store.fieldErrors.set({ title: 'must not be blank', severity: 'must not be null' });
    await fixture.whenStable();

    const messages = [...element.querySelectorAll('[data-testid="server-error"]')].map((e) =>
      e.textContent?.trim(),
    );
    expect(messages).toEqual(['Title must not be blank.', 'Severity must not be null.']);
  });
});
