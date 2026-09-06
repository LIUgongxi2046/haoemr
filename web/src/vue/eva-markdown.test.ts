import { describe, expect, it } from 'vitest';
import { renderEvaMarkdown } from './eva-markdown';

describe('EVA Markdown display', () => {
  it('renders headings, emphasis, nested lists and tables as separate semantic blocks', () => {
    const html = renderEvaMarkdown('## 本次摘要\n\n**重点**\n\n- 已知事实\n  - 待确认来源\n\n| 项目 | 状态 |\n| --- | --- |\n| 资料 | 待核对 |');
    expect(html).toContain('<h2>本次摘要</h2>');
    expect(html).toContain('<strong>重点</strong>');
    expect(html.match(/<ul>/g)).toHaveLength(2);
    expect(html).toContain('class="eva-markdown-table"');
    expect(html).toContain('<th>项目</th>');
    expect(html).toContain('<td>待核对</td>');
  });

  it('escapes injected HTML, rejects executable links and never embeds remote images', () => {
    const html = renderEvaMarkdown('<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n\n[运行](javascript:alert(1))\n\n[运行](jav&#x61;script:alert(1))\n\n![图](https://example.com/tracker.png)');
    expect(html).not.toMatch(/<(script|img|iframe)\b/i);
    expect(html).not.toMatch(/href="javascript:/i);
    expect(html).toContain('&lt;script&gt;');
  });

  it('preserves code and isolates external navigation', () => {
    const html = renderEvaMarkdown('```text\n<a onclick="bad()">literal</a>\n```\n\n[资料](https://example.com/reference)');
    expect(html).toContain('&lt;a onclick=');
    expect(html).toContain('rel="noopener noreferrer"');
    expect(html).toContain('target="_blank"');
  });
});
