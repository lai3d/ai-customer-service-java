import type { ReactNode } from 'react';

// The deliberately small subset the demo page renders -- bold, inline code, hyphen lists,
// paragraphs -- as React elements, so an operator reads what the customer saw rather than
// literal asterisks. React escapes text; nothing here is ever parsed as HTML, and there is
// no dangerouslySetInnerHTML anywhere in this UI. Links are absent on purpose: a
// model-authored href is the one markdown construct that does something rather than looks
// like something, and the text was written by a model whose input includes retrieved passages.
export function inline(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  const pattern = /\*\*([^*]+)\*\*|`([^`]+)`/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let k = 0;
  while ((m = pattern.exec(text)) !== null) {
    if (m.index > last) out.push(text.slice(last, m.index));
    if (m[1] !== undefined) out.push(<strong key={k++}>{m[1]}</strong>);
    else out.push(<code key={k++}>{m[2]}</code>);
    last = pattern.lastIndex;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

export function Markdown({ text }: { text: string }) {
  const blocks: ReactNode[] = [];
  let list: ReactNode[] | null = null;
  let paragraph: string[] = [];
  let k = 0;
  const flushList = () => { if (list) { blocks.push(<ul key={k++}>{list}</ul>); list = null; } };
  const flushParagraph = () => { if (paragraph.length) { blocks.push(<p key={k++}>{inline(paragraph.join('\n'))}</p>); paragraph = []; } };
  for (const line of text.split('\n')) {
    const item = /^\s*[-*]\s+(.*)$/.exec(line);
    if (item) { flushParagraph(); (list ??= []).push(<li key={k++}>{inline(item[1])}</li>); continue; }
    flushList();
    if (line.trim() === '') { flushParagraph(); continue; }
    paragraph.push(line);
  }
  flushList();
  flushParagraph();
  return <div className="md">{blocks}</div>;
}
