import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthStore } from '../../core/auth/auth-store';
import { OPEN_URL } from '../../core/open-url';
import { PostmortemStore } from '../postmortem-store';
import {
  ATTACHMENT_ACCEPT,
  Attachment,
  Postmortem,
  PostmortemContent,
  formatBytes,
} from '../postmortem.model';

/** The sections of a postmortem, in reading order. Limits mirror the backend's SavePostmortemRequest. */
const SECTIONS = [
  { key: 'summary', label: 'Summary', hint: 'What happened, in two or three sentences.' },
  { key: 'impact', label: 'Impact', hint: 'Who was affected, how badly, for how long.' },
  { key: 'rootCause', label: 'Root cause', hint: 'Why it happened. Keep asking why.' },
  {
    key: 'actionItems',
    label: 'Action items',
    hint: 'What will stop it happening again, and who owns it.',
  },
] as const;

@Component({
  selector: 'app-postmortem-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, ReactiveFormsModule, RouterLink],
  providers: [PostmortemStore],
  templateUrl: './postmortem-page.html',
  styleUrl: './postmortem-page.scss',
})
export class PostmortemPage implements OnInit {
  /** Bound from the `:id` route parameter (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly store = inject(PostmortemStore);
  private readonly auth = inject(AuthStore);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly openUrl = inject(OPEN_URL);

  protected readonly sections = SECTIONS;
  protected readonly accept = ATTACHMENT_ACCEPT;
  protected readonly formatBytes = formatBytes;

  /** Hiding the editor is a courtesy; incident-service rejects a viewer's save with 403 regardless. */
  protected readonly canEdit = computed(() => this.auth.hasRole('RESPONDER'));
  protected readonly resolved = computed(() => this.store.incident()?.status === 'RESOLVED');

  protected readonly form = this.fb.group({
    summary: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(4000)]],
    impact: ['', [Validators.maxLength(4000)]],
    rootCause: ['', [Validators.maxLength(4000)]],
    actionItems: ['', [Validators.maxLength(4000)]],
  });

  ngOnInit(): void {
    this.store.load(this.id()).subscribe((postmortem) => this.fill(postmortem));
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const content: PostmortemContent = {
      summary: value.summary.trim(),
      impact: value.impact.trim() || null,
      rootCause: value.rootCause.trim() || null,
      actionItems: value.actionItems.trim() || null,
    };
    // On a conflict the store keeps the user's draft in the form; nothing to do here.
    this.store.save(content).subscribe((saved) => saved && this.fill(this.store.postmortem()));
  }

  /** After a conflict: drop the draft and show what the other person saved. */
  protected discardDraft(): void {
    this.fill(this.store.postmortem());
    this.store.dismissNotice();
  }

  protected upload(input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = ''; // so picking the same file again (after an error) triggers another upload
    if (file) {
      this.store.upload(file).subscribe();
    }
  }

  protected download(attachment: Attachment): void {
    this.store.downloadUrl(attachment).subscribe((url) => url && this.openUrl(url));
  }

  protected invalid(key: (typeof SECTIONS)[number]['key']): boolean {
    const control = this.form.controls[key];
    return control.touched && control.invalid;
  }

  private fill(postmortem: Postmortem | null): void {
    this.form.reset({
      summary: postmortem?.summary ?? '',
      impact: postmortem?.impact ?? '',
      rootCause: postmortem?.rootCause ?? '',
      actionItems: postmortem?.actionItems ?? '',
    });
  }
}
