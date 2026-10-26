/** Mirrors the backend's PostmortemResponse. */
export interface Postmortem {
  incidentId: string;
  /** Sent back with the next save, like an incident's version. */
  version: number;
  summary: string;
  impact: string | null;
  rootCause: string | null;
  actionItems: string | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
}

export interface PostmortemContent {
  summary: string;
  impact: string | null;
  rootCause: string | null;
  actionItems: string | null;
}

/** `version` is null for the first draft. */
export interface SavePostmortemRequest extends PostmortemContent {
  version: number | null;
}

export interface Attachment {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  status: 'PENDING' | 'UPLOADED';
  uploadedBy: string;
  createdAt: string;
  uploadedAt: string | null;
}

/** Where to PUT the file: straight to S3, with exactly these headers, before `expiresAt`. */
export interface UploadTicket {
  attachment: Attachment;
  uploadUrl: string;
  uploadHeaders: Record<string, string>;
  expiresAt: string;
}

export interface DownloadLink {
  url: string;
  expiresAt: string;
}

// Mirror of the backend's AttachmentPolicy, so a wrong file is refused before anything is sent.
// The server checks again; this is only to fail fast.
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
export const ALLOWED_ATTACHMENT_TYPES: readonly string[] = [
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'application/pdf',
  'text/plain',
  'text/csv',
  'application/json',
];
/** What the file picker offers. */
export const ATTACHMENT_ACCEPT = [
  ...ALLOWED_ATTACHMENT_TYPES,
  '.log',
  '.txt',
  '.csv',
  '.json',
].join(',');

/** Browsers leave `type` empty for extensions they don't know, such as `.log`. */
const TYPES_BY_EXTENSION: Record<string, string> = {
  log: 'text/plain',
  txt: 'text/plain',
  csv: 'text/csv',
  json: 'application/json',
};

/** The content type to announce and upload with. */
export function contentTypeOf(file: File): string {
  if (file.type) {
    return file.type;
  }
  const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
  return TYPES_BY_EXTENSION[extension] ?? 'application/octet-stream';
}

/** Why this file cannot be attached, or null if it can. */
export function attachmentProblem(file: File): string | null {
  if (!ALLOWED_ATTACHMENT_TYPES.includes(contentTypeOf(file))) {
    return `${file.name} can't be attached. Attach images, PDFs, or text, CSV or JSON files.`;
  }
  if (file.size === 0) {
    return `${file.name} is empty.`;
  }
  if (file.size > MAX_ATTACHMENT_BYTES) {
    return `${file.name} is larger than 10 MB.`;
  }
  return null;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
