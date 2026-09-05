// The one place the UI talks to the service. Same origin through nginx (or Vite's dev proxy),
// so the session is the cookie the service set and CSRF is the readable XSRF-TOKEN cookie
// copied into a header on every mutation. A 401 anywhere means the session is gone -- expired,
// signed out elsewhere -- and the page returns to sign-in rather than showing empty tables.
// Errors are the service's {"error": "..."} bodies; 409 means reload and look again, 422 means
// the rules refused it and reloading will not help.

export type Role = 'admin' | 'support';
export interface Me { username: string; role: Role }

export type TicketState = 'open' | 'claimed' | 'resolved' | 'closed';
export interface Ticket {
  ticketNumber: string; conversationId: string; category: string; summary: string; orderNumber: string | null;
  state: TicketState; owner: string | null; createdAt: string; updatedAt: string; version: number;
}
export interface TicketEvent {
  id: number; ticketNumber: string; kind: string; actor: string; fromState?: string; toState?: string;
  fromOwner?: string; toOwner?: string; note?: string; occurredAt: string;
}
export interface TicketPage { tickets: Ticket[]; total: number; page: number; size: number }
export interface TicketDetail { ticket: Ticket; history: TicketEvent[] }
export interface SupportTicket { ticketNumber: string; category: string; summary: string; createdAt: string }
export interface TranscriptMessage { type: string; content: string; at: string }
export interface TicketConversation { conversationId: string; messages: TranscriptMessage[]; tickets: SupportTicket[]; notPersisted: string }

export interface ConversationSummary {
  conversationId: string; turns: number; firstAt: string; lastAt: string; lastOutcome: string;
  failed: number; interrupted: number; unknown: number;
}
export interface ConversationPage { conversations: ConversationSummary[]; total: number; page: number; size: number }
export interface Retrieved { rank: number; entryId: string; language: string | null; score: number }
export interface ToolCall { tool: string; outcome: string; at: string }
export interface Turn {
  turnId: string; conversationId: string; path: string; startedAt: string; endedAt: string | null; outcome: string;
  failure: string | null; model: string | null; inputTokens: number | null; outputTokens: number | null;
  traceId: string | null; question: string; answer: string | null; retrieval: Retrieved[]; toolCalls: ToolCall[];
}
export interface Feedback {
  id: number; turnId: string; conversationId: string; issue: string; note: string | null; state: 'open' | 'handled' | 'dismissed';
  conclusion: string | null; reportedBy: string; reportedAt: string; handledBy: string | null; handledAt: string | null;
  version: number; revisionId: number | null;
}
export interface FeedbackPage { reports: Feedback[]; total: number; page: number; size: number }
export interface ConversationDetail { conversationId: string; turns: Turn[]; tickets: SupportTicket[]; feedback: Feedback[]; notPersisted: string }

export interface Stat { key: string; label: string; value: number | null; definition: string }
export interface Overview { from: string; to: string; turns: Stat[]; tickets: Stat[]; feedback: Stat[]; knowledge: Stat[]; staff: Stat[] }

export interface StaffAccount { username: string; role: Role; enabled: boolean; createdAt: string; createdBy: string | null }

export interface KnowledgeRevision {
  id: number; entryId: string; language: string; question: string; answer: string; state: 'draft' | 'published' | 'superseded';
  createdAt: string; createdBy: string; note: string | null;
}
export interface KnowledgeEntry { entryId: string; category: string; retired: boolean; createdAt: string; createdBy: string; revisions: KnowledgeRevision[] }
export interface KnowledgeVersion {
  version: string; state: 'building' | 'ready' | 'active' | 'failed' | 'retired'; documentCount: number | null;
  createdAt: string; createdBy: string; activatedAt: string | null; note: string | null; error: string | null;
}
export interface Versions { active: string | null; versions: KnowledgeVersion[] }
export interface Passage { id: string; text: string; score: number | null; metadata: Record<string, unknown> }

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}

const BASE = '/admin/api';

export function csrfToken(cookie: string = typeof document === 'undefined' ? '' : document.cookie): string {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(cookie);
  return match ? decodeURIComponent(match[1]) : '';
}

let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn; }

export async function errorMessage(res: Response): Promise<string> {
  try { const body = await res.json() as { error?: string }; if (body && typeof body.error === 'string') return body.error; } catch { /* not JSON */ }
  return res.status === 403 ? 'Not allowed.' : `The request failed (${res.status}).`;
}

async function call<T>(method: string, path: string, body?: unknown, signOutOn401 = true): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin' });
  if (res.status === 204) return undefined as T;
  if (!res.ok) {
    const message = await errorMessage(res);
    if (res.status === 401 && signOutOn401) onUnauthorized?.();
    throw new ApiError(res.status, message);
  }
  return await res.json() as T;
}

export function query(params: Record<string, string | number | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : '';
}

export const api = {
  csrf: () => call<void>('GET', '/csrf', undefined, false),
  login: (username: string, password: string) => call<Me>('POST', '/login', { username, password }, false),
  logout: () => call<void>('POST', '/logout', {}),
  me: () => call<Me>('GET', '/me'),

  overview: (f: { from?: string; to?: string }) => call<Overview>('GET', '/overview' + query(f)),

  tickets: (f: { state?: string; owner?: string; page?: number; size?: number }) => call<TicketPage>('GET', '/tickets' + query(f)),
  ticket: (n: string) => call<TicketDetail>('GET', `/tickets/${encodeURIComponent(n)}`),
  ticketConversation: (n: string) => call<TicketConversation>('GET', `/tickets/${encodeURIComponent(n)}/conversation`),
  ticketAction: (n: string, action: string, expectedVersion: number, extra: { assignee?: string; text?: string } = {}) =>
    call<Ticket>('POST', `/tickets/${encodeURIComponent(n)}/${action}`, { expectedVersion, ...extra }),

  conversations: (f: { conversationId?: string; outcome?: string; from?: string; to?: string; page?: number; size?: number }) =>
    call<ConversationPage>('GET', '/conversations' + query(f)),
  conversation: (id: string) => call<ConversationDetail>('GET', `/conversations/${encodeURIComponent(id)}`),

  feedback: (f: { state?: string; page?: number; size?: number }) => call<FeedbackPage>('GET', '/feedback' + query(f)),
  flag: (turnId: string, issue: string, note: string) => call<Feedback>('POST', '/feedback', { turnId, issue, note }),
  handleFeedback: (id: number, state: 'handled' | 'dismissed', conclusion: string, expectedVersion: number, revisionId?: number) =>
    call<Feedback>('POST', `/feedback/${id}/handle`, { state, conclusion, expectedVersion, revisionId: revisionId ?? null }),

  entries: () => call<KnowledgeEntry[]>('GET', '/knowledge/entries'),
  createEntry: (id: string, category: string) => call<KnowledgeEntry>('POST', `/knowledge/entries/${encodeURIComponent(id)}`, { category }),
  saveDraft: (id: string, language: string, question: string, answer: string, note: string) =>
    call<KnowledgeRevision>('PUT', `/knowledge/entries/${encodeURIComponent(id)}/drafts/${encodeURIComponent(language)}`, { question, answer, note }),
  discardDraft: (id: string, language: string) => call<void>('DELETE', `/knowledge/entries/${encodeURIComponent(id)}/drafts/${encodeURIComponent(language)}`),
  retire: (id: string, retired: boolean) => call<KnowledgeEntry>('POST', `/knowledge/entries/${encodeURIComponent(id)}/retire`, { retired }),
  versions: () => call<Versions>('GET', '/knowledge/versions'),
  publish: (note: string, expectedActive: string | null) => call<{ started: boolean }>('POST', '/knowledge/publish', { note, expectedActive }),
  rollback: (version: string, expectedActive: string | null) => call<KnowledgeVersion>('POST', '/knowledge/rollback', { version, expectedActive }),
  preview: (text: string, version: string | null, topK = 5) => call<Passage[]>('POST', '/knowledge/preview', { text, version, topK }),

  staff: () => call<StaffAccount[]>('GET', '/staff'),
  createStaff: (username: string, password: string, role: Role) => call<StaffAccount>('POST', '/staff', { username, password, role }),
};
