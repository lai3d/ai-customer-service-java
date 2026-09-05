import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { Markdown } from './components/Markdown';

const render = (text: string) => renderToStaticMarkup(createElement(Markdown, { text }));

describe('Markdown', () => {
  it('renders bold, code and hyphen lists as elements', () => {
    expect(render('Sorry. I raised **TKT-4701** for `ORD-10042`.\n\n- one\n- two'))
        .toBe('<div class="md"><p>Sorry. I raised <strong>TKT-4701</strong> for <code>ORD-10042</code>.</p><ul><li>one</li><li>two</li></ul></div>');
  });
  it('never turns text into markup: a link or a tag stays literal', () => {
    const out = render('[click](javascript:alert(1)) <img src=x onerror=alert(1)>');
    expect(out).toContain('[click](javascript:alert(1))');
    expect(out).toContain('&lt;img');
    expect(out).not.toContain('<img');
    expect(out).not.toContain('<a ');
  });
  it('leaves an unmatched delimiter literal', () => {
    expect(render('half **bold')).toBe('<div class="md"><p>half **bold</p></div>');
  });
});
