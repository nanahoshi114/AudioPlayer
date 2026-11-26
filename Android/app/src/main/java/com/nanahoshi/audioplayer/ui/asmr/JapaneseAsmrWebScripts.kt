package com.nanahoshi.audioplayer.ui.asmr

/**
 * Japanese ASMR / Buzzheavier 可见网页内注入的脚本。脚本只读取页面状态，不包含 URL 令牌以外的凭证；
 * 返回值统一为 JSON 字符串，由 JapaneseAsmrWebBridge.kt 中的纯函数解码。
 */
internal object JapaneseAsmrWebScripts {

// 只读取主播放器、作品号、封面与章节。站点验证和会话始终由可见网页处理。
val READ_PUBLIC_PLAYER = """
    (() => {
        const players = Array.from(document.querySelectorAll('.cleanPlayer video, .cleanPlayer audio'));
        const legacy = document.querySelector('#basic-playlist li a[data-url]');
        if (players.length === 0 && !legacy) return null;
        const output = document.createElement('template');
        const root = output.content;
        const inertDocument = root.ownerDocument;
        const copy = (parent, source, tag, attributes, text) => {
            if (!source) return null;
            const target = inertDocument.createElement(tag);
            for (const name of attributes) {
                if (source.hasAttribute(name)) target.setAttribute(name, source.getAttribute(name));
            }
            if (text) target.textContent = source.textContent;
            parent.appendChild(target);
            return target;
        };
        copy(root, document.querySelector('h1.page-title'), 'h1', ['class'], true);
        copy(root, document.querySelector('#voice_actors'), 'span', ['id'], true);
        copy(root, document.querySelector('time[datetime]'), 'time', ['datetime'], false);
        copy(root, document.querySelector('meta[property="og:image"]'), 'meta', ['property', 'content'], false);
        const entry = inertDocument.createElement('div');
        entry.className = 'entry-content';
        for (const line of document.querySelectorAll('.entry-content p')) {
            if (/^\s*RJ Code:/i.test(line.textContent)) copy(entry, line, 'p', [], true);
        }
        copy(entry, document.querySelector('.entry-content img[data-src]'), 'img', ['data-src'], false);
        root.appendChild(entry);
        const cleanPlayer = inertDocument.createElement('div');
        cleanPlayer.className = 'cleanPlayer';
        for (const player of players) {
            const node = copy(cleanPlayer, player, player.localName, ['title', 'descr', 'src', 'type'], false);
            for (const source of player.querySelectorAll('source[src]')) {
                copy(node, source, 'source', ['src', 'type'], false);
            }
        }
        root.appendChild(cleanPlayer);
        const chapters = document.querySelector('#plyr-chapter-playlist');
        if (chapters) root.appendChild(chapters.cloneNode(true));
        for (const link of document.querySelectorAll('a[href]')) {
            if (link.pathname === '/dlc.php' || /Download/i.test(link.textContent)) {
                const node = copy(entry, link, 'a', ['href'], true);
                node.setAttribute('href', link.href);
            }
        }
        if (legacy) {
            const playlist = inertDocument.createElement('ul');
            playlist.id = 'basic-playlist';
            const item = inertDocument.createElement('li');
            copy(item, legacy, 'a', ['data-url'], false);
            playlist.appendChild(item);
            root.appendChild(playlist);
        }
        const html = output.innerHTML;
        return html.length <= 512000 ? JSON.stringify({ pageUrl: location.href, html }) : null;
    })()
""".trimIndent()

// 超时诊断只报告按钮类型、请求状态和响应类型，不包含 URL 或令牌。
val BUZZ_DIAGNOSTIC = """
    (() => {
        const elements = Array.from(document.querySelectorAll('button, a, [role="button"]'));
        const el = elements.find((x) => /download\s*file/i.test((x.textContent || '').trim())) ||
            document.querySelector('[hx-get*="download"], a[href*="/download"], [data-url*="download"]');
        const last = window.__jaBuzzLast || {};
        return (el ? el.tagName : 'NONE') + (el && el.hasAttribute('hx-get') ? '|HX' : '') +
            (el && el.hasAttribute('href') ? '|HREF' : '') + '|XHR' + (window.__jaBuzzXhrCount || 0) +
            '|' + (last.status || 0) + '|' + (last.type || '') + '|' + (last.redirect ? 'R' : '-') +
            '|' + (window.__jaBuzzDirect ? 'D' : '-') + (window.__jaBuzzEndpointLink ? 'E' : '-') +
            (window.__jaBuzzFile ? 'V' : '-') +
            '|F' + (window.__jaBuzzFollowStatus || 0) + '|' + (window.__jaBuzzFollowType || '') +
            '|' + (window.__jaBuzzFollowHost || '') + (window.__jaBuzzFollowError ? 'X' : '');
    })()
""".trimIndent()

// 监听网页自己的 Download File 请求；HTMX 的 HX-Redirect、XHR/fetch 的最终地址以及
// 返回 HTML/JSON 中间的下载链接都不一定触发主框架导航。
val BUZZ_CAPTURE = """
    (() => {
        if (window.__jaBuzzCapture) return;
        window.__jaBuzzCapture = true;
        window.__jaBuzzDirect = null;
        window.__jaBuzzNext = null;
        window.__jaBuzzFile = null;
        window.__jaBuzzLast = null;
        window.__jaBuzzFollowStatus = 0;
        window.__jaBuzzFollowType = '';
        window.__jaBuzzFollowHost = '';
        window.__jaBuzzFollowError = false;
        // 保存页面原生 fetch；follow() 必须用它发请求，否则会进入下面被改写的 window.fetch，污染计数与最近一次响应。
        const originalFetch = window.fetch;
        const absolute = (url) => {
            if (!url) return null;
            try { return new URL(url, location.href).href; } catch (e) { return null; }
        };
        const endpointLike = (url) => {
            try {
                const path = new URL(url, location.href).pathname;
                return /\/[A-Za-z0-9_-]+\/download$/.test(path) || /\/d\/[A-Za-z0-9_-]+$/.test(path);
            } catch (e) { return false; }
        };
        const useful = (url) => {
            const value = absolute(url);
            if (!value) return null;
            try {
                const host = new URL(value).hostname;
                if (!(/(^|\.)buzzheavier\.com$/.test(host) || host === 'mega.nz' || host === 'mega.co.nz')) return null;
            } catch (e) { return null; }
            return endpointLike(value) ? null : value;
        };
        const scan = (text) => {
            if (!text || text.length > 1000000) return null;
            const parsed = new DOMParser().parseFromString(text, 'text/html');
            const links = Array.from(parsed.querySelectorAll('a[href], [hx-get], [data-url], [data-clipboard-text]'))
                .map((el) => el.getAttribute('href') || el.getAttribute('hx-get') ||
                    el.getAttribute('data-url') || el.getAttribute('data-clipboard-text'));
            for (const link of links) {
                const value = absolute(link);
                if (!value) continue;
                if (endpointLike(value)) { window.__jaBuzzEndpointLink = true; continue; }
                const url = useful(value);
                if (url) return url;
            }
            const urls = text.match(/https?:\/\/[^"'\s<>]+/g) || [];
            for (const raw of urls) {
                const value = absolute(raw.replace(/&amp;/g, '&'));
                if (!value) continue;
                if (endpointLike(value)) { window.__jaBuzzEndpointLink = true; continue; }
                const url = useful(value);
                if (url) return url;
            }
            return null;
        };
        const report = (url) => {
            const value = useful(url);
            if (value) window.__jaBuzzDirect = value;
        };
        const record = (xhr) => {
            try {
                window.__jaBuzzLast = {
                    status: xhr.status,
                    type: (xhr.getResponseHeader('Content-Type') || '').split(';')[0].slice(0, 40),
                    redirect: !!(xhr.getResponseHeader('HX-Redirect') || xhr.getResponseHeader('Location')),
                };
            } catch (e) {}
        };
        // 浏览器会话内继续跟随 HX-Redirect/Location，直到拿到真正的文件地址。
        const follow = (start) => {
            if (!start || !originalFetch || window.__jaBuzzFollowing) return;
            window.__jaBuzzFollowing = true;
            let url = absolute(start);
            let hops = 0;
            const step = () => {
                if (!url || hops++ > 6) { window.__jaBuzzFollowing = false; return; }
                originalFetch.call(window, url, {
                    // HX-Redirect 的目标是正常文档/文件地址；不要再带 HTMX 请求头，
                    // 否则服务端可能继续返回 204 + HX-Redirect，形成循环。
                    headers: { 'Referer': location.href },
                    credentials: 'include',
                    redirect: 'follow',
                }).then((response) => {
                    window.__jaBuzzFollowStatus = response.status;
                    window.__jaBuzzFollowType = (response.headers.get('Content-Type') || '').split(';')[0].slice(0, 30);
                    try {
                        const host = new URL(response.url).hostname;
                        window.__jaBuzzFollowHost = /(^|\.)buzzheavier\.com$/.test(host) ? 'B' : 'O';
                    } catch (e) { window.__jaBuzzFollowHost = '?'; }
                    const header = response.headers.get('HX-Redirect') || response.headers.get('Location');
                    if (header) { url = absolute(header); step(); return; }
                    const type = (response.headers.get('Content-Type') || '').toLowerCase();
                    const attachment = (response.headers.get('Content-Disposition') || '').toLowerCase().includes('attachment');
                    if (response.url && response.url !== location.href && (!type.includes('text/html') || attachment)) {
                        window.__jaBuzzFile = response.url;
                        window.__jaBuzzDirect = response.url;
                        window.__jaBuzzFollowing = false;
                        return;
                    }
                    if (type.includes('text/html') || type.includes('application/json')) {
                        response.clone().text().then((text) => {
                            const link = scan(text);
                            if (link) { url = link; step(); }
                            else { window.__jaBuzzNext = response.url || url; window.__jaBuzzFollowing = false; }
                        }).catch(() => { window.__jaBuzzFollowing = false; });
                    } else {
                        window.__jaBuzzNext = response.url || url;
                        window.__jaBuzzFollowing = false;
                    }
                }).catch(() => { window.__jaBuzzFollowError = true; window.__jaBuzzFollowing = false; });
            };
            step();
        };
        const pick = (xhr) => {
            if (!xhr) return null;
            try {
                const header = xhr.getResponseHeader('HX-Redirect') || xhr.getResponseHeader('Location');
                if (header) {
                    window.__jaBuzzNext = absolute(header);
                    report(header);
                    follow(header);
                } else if (xhr.responseURL && xhr.responseURL !== location.href) {
                    report(xhr.responseURL);
                }
                if (!window.__jaBuzzDirect) {
                    let text = null;
                    if (xhr.responseType === '' || xhr.responseType === 'text') text = xhr.responseText;
                    else if (xhr.responseType === 'json') text = JSON.stringify(xhr.response);
                    if (text) report(scan(text));
                }
            } catch (e) {}
        };
        document.addEventListener('htmx:afterRequest', (event) => {
            try { pick(event.detail && event.detail.xhr); } catch (e) {}
        });
        const open = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url, ...rest) {
            window.__jaBuzzXhrCount = (window.__jaBuzzXhrCount || 0) + 1;
            this.addEventListener('loadend', () => { record(this); pick(this); });
            return open.call(this, method, url, ...rest);
        };
        if (originalFetch) {
            window.fetch = function(...args) {
                window.__jaBuzzXhrCount = (window.__jaBuzzXhrCount || 0) + 1;
                return originalFetch.apply(this, args).then((response) => {
                    try {
                        const type = (response.headers.get('Content-Type') || '').toLowerCase();
                        window.__jaBuzzLast = {
                            status: response.status,
                            type: type.split(';')[0].slice(0, 40),
                            redirect: !!(response.headers.get('HX-Redirect') || response.headers.get('Location')),
                        };
                        const header = response.headers.get('HX-Redirect') || response.headers.get('Location');
                        if (header) {
                            window.__jaBuzzNext = absolute(header);
                            report(header);
                        } else if (response.url && response.url !== location.href) {
                            report(response.url);
                        }
                        if (!window.__jaBuzzDirect && (type.includes('text/html') || type.includes('application/json'))) {
                            response.clone().text().then((text) => report(scan(text))).catch(() => {});
                        }
                    } catch (e) {}
                    return response;
                });
            };
        }
        const openWindow = window.open;
        if (openWindow) {
            window.open = function(url, ...rest) {
                try { report(url); } catch (e) {}
                return openWindow.call(this, url, ...rest);
            };
        }
    })()
""".trimIndent()

val CLICK_BUZZ_DOWNLOAD = """
    (() => {
        const elements = Array.from(document.querySelectorAll('button, a, [role="button"]'));
        const byText = elements.find((el) => /download\s*file/i.test((el.textContent || '').trim())) ||
            elements.find((el) => /^\s*download\s*$/i.test((el.textContent || '').trim()));
        const button = byText ||
            document.querySelector('[hx-get*="download"], a[href*="/download"], [data-url*="download"]');
        if (!button) return false;
        button.click();
        return true;
    })()
""".trimIndent()

val READ_DOWNLOAD_PAGE = """
    (() => {
        const html = document.documentElement.outerHTML;
        return html.length <= 512000 ? JSON.stringify({ pageUrl: location.href, html }) : null;
    })()
""".trimIndent()

// 一次读取脚本捕获到的直链、文件地址与中间地址；没有任何结果时返回 null。
val BUZZ_PROBE = """
    (() => {
        const direct = window.__jaBuzzDirect || null;
        const file = window.__jaBuzzFile || null;
        const next = window.__jaBuzzNext || null;
        return (direct || file || next) ? JSON.stringify({ direct, file, next }) : null;
    })()
""".trimIndent()
}
