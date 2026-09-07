/** A list typed as one line: commas or newlines between items, blanks dropped, each trimmed. */
export function splitList(text: string): string[] {
  return text.split(/[,\n]+/).map(s => s.trim()).filter(Boolean);
}

export function joinList(items: string[]): string {
  return items.join(', ');
}

/** A ratio as a percentage with one decimal, or a dash when there is nothing to divide. */
export function percent(part: number, whole: number): string {
  return whole === 0 ? '—' : `${Math.round((part / whole) * 1000) / 10}%`;
}
