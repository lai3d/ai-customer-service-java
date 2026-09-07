import { describe, expect, it } from 'vitest';
import type { PilotReport } from './api';
import { lines } from './pages/Report';

const report: PilotReport = {
  tenant: 'acme', from: '2026-08-08T00:00:00Z', to: '2026-09-07T00:00:00Z', conversations: 40, turns: 90, escalated: 6, flagged: 2,
  deflectionRate: 0.85, evaluation: { runId: 3, finishedAt: '2026-09-06T10:00:00Z', cases: 43, passed: 39, passRate: 0.907, retrievalHitRate: 1, answerPassRate: 0.93 },
  inputTokens: 120000, outputTokens: 9000, unmeteredTurns: 1, costUsd: 0.825, unpricedModels: [], costPerConversationUsd: 0.0206, definitions: {},
};

describe('the report as text', () => {
  it('says the three things a customer pays for, in order', () => {
    const text = lines(report);
    expect(text[1]).toBe('Conversations: 40 (90 turns)');
    expect(text[2]).toBe('Settled without a person: 85% (6 escalated, 2 flagged by staff)');
    expect(text[3]).toContain('90.7% of 43 golden cases passed');
    expect(text[4]).toContain('$0.82');
    expect(text[4]).toContain('1 turns without usage');
    expect(text[5]).toBe('Per conversation: $0.0206');
  });
  it('says when there is no run and no price', () => {
    const text = lines({ ...report, evaluation: null, costUsd: 0, unpricedModels: ['mystery'], costPerConversationUsd: null, unmeteredTurns: 0 });
    expect(text[3]).toBe('Answer quality: no evaluation run yet');
    expect(text[4]).toContain('unpriced: mystery');
    expect(text[5]).toBe('Per conversation: —');
  });
});
