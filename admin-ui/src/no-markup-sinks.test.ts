import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

// The line the demo page and the old static admin held: text this UI shows was written by
// customers, by staff, or by a model whose input includes retrieved passages, and none of it
// may ever become markup. React escapes text by default; the one way around that is the
// property named below, and this test is the grep that says it is not used.
function sources(dir: string): string[] {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? sources(path) : /\.(tsx?|css|html)$/.test(name) && !name.endsWith('.test.ts') ? [path] : [];
  });
}

describe('markup sinks', () => {
  it('no source file uses dangerouslySetInnerHTML, innerHTML, eval or document.write', () => {
    const offenders: string[] = [];
    for (const file of sources(join(__dirname))) {
      const code = readFileSync(file, 'utf8').split('\n').filter(line => !line.trim().startsWith('//')).join('\n');
      for (const sink of ['dangerouslySetInnerHTML', 'innerHTML', 'outerHTML', 'insertAdjacentHTML', 'document.write', 'eval(', 'new Function']) {
        if (code.includes(sink)) offenders.push(`${file}: ${sink}`);
      }
    }
    expect(offenders).toEqual([]);
  });
});
