package net.conw.unit.browser.page

import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.BrowserTab
import org.json.JSONObject

/** A global preference, applied inside each tab's document after its next page load. */
internal class BrowserReader(private val activity: BrowserActivity) {
    val enabled
        get() = activity.preferences.reader

    private val extraction by lazy {
        activity.assets.run {
            "const module={exports:{}};const exports=module.exports;" +
                open("reader/Readability.min.js").bufferedReader().use { it.readText() } +
                "\n" +
                open("reader/purify.min.js").bufferedReader().use { it.readText() } +
                "\nconst article=(function(){" +
                open("reader/extract.js").bufferedReader().use { it.readText() } +
                "\n})();"
        }
    }

    fun toggle() {
        activity.preferences.reader = !enabled
        activity.current()?.reload()
    }

    fun apply(tab: BrowserTab) {
        val background = color(R.color.background)
        val text = color(R.color.text)
        val muted = color(R.color.muted)
        val accent = color(R.color.accent)
        val css =
            """
            body{margin:0;padding:24px;color:$text;background:$background;font:18px/1.75 Georgia,serif;overflow-wrap:anywhere}
            article{max-width:42rem;margin:auto}h1{font:600 28px/1.3 system-ui;margin:0 0 12px}h2,h3{line-height:1.4}
            .byline{font:14px/1.5 system-ui;color:$muted;margin-bottom:24px}a{color:$accent}img{max-width:100%;height:auto}
            figure{margin:24px 0}pre{white-space:pre-wrap}table{display:block;overflow:auto}
            blockquote{border-inline-start:3px solid $accent;margin:20px 0;padding-inline-start:16px}
            """
                .trimIndent()
        val quotedCss = JSONObject.quote(css)
        val script =
            """
            (function(){
                $extraction
                if(!article||!article.content)return false;
                const head=document.createElement('head');
                const viewport=document.createElement('meta');
                viewport.name='viewport';viewport.content='width=device-width,initial-scale=1';
                head.appendChild(viewport);
                const policy=document.createElement('meta');
                policy.httpEquiv='Content-Security-Policy';
                policy.content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; form-action 'none'";
                head.appendChild(policy);
                const title=document.createElement('title');title.textContent=article.title;
                head.appendChild(title);
                const style=document.createElement('style');style.textContent=$quotedCss;
                head.appendChild(style);
                const body=document.createElement('body');
                const content=document.createElement('article');content.innerHTML=article.content;
                const heading=document.createElement('h1');heading.textContent=article.title;
                const byline=document.createElement('div');byline.className='byline';byline.textContent=article.byline;
                content.prepend(heading,byline);body.appendChild(content);
                document.documentElement.setAttribute('dir',article.dir);
                document.documentElement.replaceChildren(head,body);
                window.scrollTo(0,0);
                return true;
            })()
            """
                .trimIndent()
        tab.evaluate(script) { result ->
            if (result != "true") activity.message(R.string.reader_unavailable)
        }
    }

    private fun color(id: Int) = "#%06x".format(activity.getColor(id) and 0xffffff)
}
