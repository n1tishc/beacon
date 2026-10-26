import { Attachment, Postmortem, UploadTicket } from '../postmortem.model';

export function aPostmortem(overrides: Partial<Postmortem> = {}): Postmortem {
  return {
    incidentId: 'abc',
    version: 0,
    summary: 'Checkout was down for 9 minutes.',
    impact: null,
    rootCause: null,
    actionItems: null,
    createdBy: 'riley',
    createdAt: '2026-01-15T11:00:00Z',
    updatedBy: 'riley',
    updatedAt: '2026-01-15T11:00:00Z',
    ...overrides,
  };
}

export function anAttachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: crypto.randomUUID(),
    fileName: 'checkout.log',
    contentType: 'text/plain',
    sizeBytes: 2048,
    status: 'UPLOADED',
    uploadedBy: 'riley',
    createdAt: '2026-01-15T11:05:00Z',
    uploadedAt: '2026-01-15T11:05:01Z',
    ...overrides,
  };
}

export function anUploadTicket(attachment: Attachment): UploadTicket {
  return {
    attachment,
    uploadUrl: `http://localhost:4566/beacon-attachments/incidents/abc/attachments/${attachment.id}?X-Amz-Signature=sig`,
    uploadHeaders: { 'content-type': attachment.contentType },
    expiresAt: '2026-01-15T11:10:00Z',
  };
}
