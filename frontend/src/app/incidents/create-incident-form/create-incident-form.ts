import { TitleCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { IncidentStore } from '../incident-store';
import { SEVERITIES, Severity } from '../incident.model';

@Component({
  selector: 'app-create-incident-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, TitleCasePipe],
  templateUrl: './create-incident-form.html',
  styleUrl: './create-incident-form.scss',
})
export class CreateIncidentForm {
  protected readonly store = inject(IncidentStore);
  protected readonly severities = SEVERITIES;

  // Validators mirror the backend's CreateIncidentRequest constraints.
  protected readonly form = inject(NonNullableFormBuilder).group({
    title: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(200)]],
    description: ['', [Validators.maxLength(4000)]],
    severity: ['HIGH' as Severity, [Validators.required]],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { title, description, severity } = this.form.getRawValue();
    this.store
      .create({ title: title.trim(), description: description.trim() || null, severity })
      .subscribe(() => this.form.reset());
  }
}
