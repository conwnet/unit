try {
    if (document.getElementsByTagName('*').length > 50000) return null;
    const article = new Readability(document.cloneNode(true), { maxElemsToParse: 50000 }).parse();
    if (!article || article.length < 200 || article.content.length > 500000) return null;
    const content = module.exports.sanitize(article.content, {
        USE_PROFILES: { html: true, svg: true, mathMl: true },
        FORBID_TAGS: ['style', 'form', 'input', 'button', 'textarea', 'select', 'video', 'audio', 'iframe', 'object', 'embed'],
        FORBID_ATTR: ['style'],
        RETURN_DOM: true
    });
    if (content.textContent.trim().length < 200) return null;
    const html = content.innerHTML;
    if (html.length > 500000) return null;
    return {
        title: article.title,
        byline: article.byline || '',
        dir: article.dir || 'auto',
        content: html
    };
} catch (_) {
    return null;
}
