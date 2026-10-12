(function () {
    const found = new Map();
    const playlist = /\.(?:m3u8|mpd)(?:[?#]|$)/i;
    function visit(doc, depth) {
        for (const video of doc.querySelectorAll('video')) {
            if (found.size >= 50) break;
            if (video.mediaKeys || /^(?:blob|data):/i.test(video.currentSrc)) continue;
            const sources = [{ url: video.currentSrc || video.src, type: video.getAttribute('type') || '' }];
            for (const source of video.querySelectorAll('source')) sources.push({ url: source.src, type: source.type });
            for (const source of sources) {
                if (!/^https?:\/\//i.test(source.url) || playlist.test(source.url) || /mpegurl|dash\+xml/i.test(source.type)) continue;
                if (!found.has(source.url)) found.set(source.url, {
                    url: source.url,
                    title: (video.getAttribute('aria-label') || video.title || '').slice(0, 120)
                });
            }
        }
        if (depth < 4 && found.size < 50) {
            for (const frame of doc.querySelectorAll('iframe,frame')) {
                try { if (frame.contentDocument) visit(frame.contentDocument, depth + 1); } catch (_) {}
            }
        }
    }
    visit(document, 0);
    return Array.from(found.values()).slice(0, 50);
})()
