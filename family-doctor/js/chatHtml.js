/* 模型原文和缓存原文都只在展示时净化，禁止直接写入 v-html。 */
window.chatHtml = {
    render(content) {
        const raw = typeof content === 'string' ? content : '';
        try {
            if (window.DOMPurify && window.DOMPurify.isSupported !== false) {
                return window.DOMPurify.sanitize(raw, {
                    ALLOWED_TAGS: ['div', 'section', 'p', 'br', 'strong', 'b', 'em', 'i',
                        'u', 's', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4',
                        'blockquote', 'pre', 'code', 'hr', 'a',
                        'table', 'thead', 'tbody', 'tr', 'th', 'td'],
                    ALLOWED_ATTR: ['href', 'title'],
                    ALLOW_DATA_ATTR: false,
                    ALLOW_ARIA_ATTR: false,
                    ALLOWED_URI_REGEXP: /^(?:https?:\/\/|#|\/(?!\/))/i
                });
            }
        } catch (_) { /* 净化工具异常时回退为纯文本，绝不返回未净化的 HTML。 */ }
        return raw.replace(/[&<>"']/g, character => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        })[character]);
    }
};
