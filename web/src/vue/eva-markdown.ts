import MarkdownIt from 'markdown-it';

// Model output is untrusted: keep HTML escaped and prevent embedded resources.
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false, typographer: false });
markdown.disable('image');
const defaultLink = markdown.renderer.rules.link_open;
markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
  tokens[index]!.attrSet('target', '_blank');
  tokens[index]!.attrSet('rel', 'noopener noreferrer');
  return defaultLink ? defaultLink(tokens, index, options, env, renderer) : renderer.renderToken(tokens, index, options);
};
markdown.renderer.rules.table_open = () => '<div class="eva-markdown-table" tabindex="0" role="region" aria-label="回复表格，可横向滚动"><table>';
markdown.renderer.rules.table_close = () => '</table></div>\n';

export function renderEvaMarkdown(source: string): string {
  return markdown.render(source);
}
