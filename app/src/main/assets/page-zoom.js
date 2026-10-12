// Open page-scale limits only when the user requests Zoom; keep layout and font settings intact.
(() => {
    document.querySelectorAll('meta[name="viewport" i]').forEach(meta => {
        const layout = meta.content.split(/[;,]/).filter(part =>
            !/^\s*(user-scalable|minimum-scale|maximum-scale)\s*=/i.test(part));
        const content = [...layout, 'user-scalable=yes', 'minimum-scale=0.25', 'maximum-scale=10'].join(',');
        if (meta.content !== content) meta.content = content;
    });

    // Commit viewport limits through a layout pass; restore the page width before painting.
    const body = document.body;
    if (body) {
        const width = body.style.getPropertyValue('width');
        const priority = body.style.getPropertyPriority('width');
        const measured = parseFloat(getComputedStyle(body).width);
        if (Number.isFinite(measured)) {
            body.style.setProperty('width', `${measured + 1}px`, 'important');
            body.getBoundingClientRect();
            if (width) body.style.setProperty('width', width, priority);
            else body.style.removeProperty('width');
        }
    }
})();
