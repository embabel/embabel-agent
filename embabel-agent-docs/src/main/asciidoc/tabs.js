/**
 * Minified by jsDelivr using Terser v5.39.0.
 * Original file: /npm/@asciidoctor/tabs@1.0.0-beta.6/dist/js/tabs.js
 *
 * Do NOT use SRI with dynamically generated files! More information: https://www.jsdelivr.com/using-sri-with-dynamic-files
 */
!function () {/*! Asciidoctor Tabs | Copyright (c) 2018-present Dan Allen | MIT License */
    "use strict";
    var t = (document.currentScript || {}).dataset || {}, e = Array.prototype.forEach;

    function a(e) {
        var a = this.tab, n = this.tabs || (this.tabs = a.closest(".tabs")),
            s = this.panel || (this.panel = document.getElementById(a.getAttribute("aria-controls")));
        if (i(n, ".tablist .tab", "tab").forEach((function (t) {
            r(t, t === a)
        })), i(n, ".tabpanel", "tabpanel").forEach((function (t) {
            o(t, t !== s)
        })), !this.isSync && "syncStorageKey" in t && "syncGroupId" in n.dataset) {
            var c = t.syncStorageKey + "-" + n.dataset.syncGroupId;
            window[(t.syncStorageScope || "local") + "Storage"].setItem(c, a.dataset.syncId)
        }
        if (e) {
            var l = window.location, d = l.hash ? l.href.indexOf("#") : -1;
            ~d && window.history.replaceState(null, "", l.href.slice(0, d)), e.preventDefault()
        }
    }

    function n(t) {
        a.call(this, t);
        var n = this.tabs, s = this.tab, o = n.getBoundingClientRect().y;
        e.call(document.querySelectorAll(".tabs"), (function (t) {
            t !== n && t.dataset.syncGroupId === n.dataset.syncGroupId && i(t, ".tablist .tab", "tab").forEach((function (e) {
                e.dataset.syncId === s.dataset.syncId && a.call({tabs: t, tab: e, isSync: !0})
            }))
        }));
        var r = n.getBoundingClientRect().y - o;
        r && (r = Math.round(r)) && window.scrollBy({top: r, behavior: "instant"})
    }

    function i(t, e, a) {
        var n = t.querySelector(e);
        if (!n) return [];
        for (var i = [n]; (n = n.nextElementSibling) && n.classList.contains(a);) i.push(n);
        return i
    }

    function s(t, a, n) {
        e.call(t, (function (t) {
            t.classList[n](a)
        }))
    }

    function o(t, e) {
        t.classList[(t.hidden = e) ? "add" : "remove"]("is-hidden")
    }

    function r(t, e) {
        t.setAttribute("aria-selected", "" + e), t.classList[e ? "add" : "remove"]("is-selected"), t.tabIndex = e ? 0 : -1
    }

    function c() {
        var t = window.location.hash.slice(1);
        if (t) {
            var e = document.getElementById(~t.indexOf("%") ? decodeURIComponent(t) : t);
            e && e.classList.contains("tab") && ("syncId" in e.dataset ? n.call({tab: e}) : a.call({tab: e}))
        }
    }

    !function (i) {
        if (!i.length) return;
        e.call(i, (function (i) {
            var s, c = i.classList.contains("is-sync") ? {} : void 0, l = i.querySelector(".tablist ul");
            if (l.setAttribute("role", "tablist"), e.call(l.querySelectorAll("li"), (function (t, e) {
                var l, d, u;
                t.tabIndex = -1, t.setAttribute("role", t.classList.add("tab") || "tab"), !(l = t.id) && (d = t.querySelector("a[id]")) && (l = t.id = d.parentNode.removeChild(d).id);
                var b = l && i.querySelector('.tabpanel[aria-labelledby~="' + l + '"]');
                if (!b) return e ? void 0 : r(t, !0);
                c && (!((u = t.textContent.trim()) in c) || (u = void 0)) && (c[t.dataset.syncId = u] = t), e || c && (s = {
                    tab: t,
                    panel: b
                }) ? o(b, !0) : r(t, !0), t.setAttribute("aria-controls", b.id), b.setAttribute("role", "tabpanel");
                var y = void 0 === u ? a : n;
                t.addEventListener("click", y.bind({tabs: i, tab: t, panel: b}))
            })), i.closest(".tabpanel") || e.call(i.querySelectorAll(".tabpanel table.tableblock"), (function (t) {
                var e = Object.assign(document.createElement("div"), {className: "tablecontainer"});
                t.parentNode.insertBefore(e, t).appendChild(t)
            })), s) {
                for (var d, u, b = 0, y = i.classList, f = y.length; b !== f; b++) if ((u = y.item(b)).startsWith("data-sync-group-id=")) {
                    i.dataset.syncGroupId = d = y.remove(u) || u.slice(19).replace(/\u00a0/g, " ");
                    break
                }
                void 0 === d && (i.dataset.syncGroupId = d = Object.keys(c).sort().join("|"));
                var p = "syncStorageKey" in t && window[(t.syncStorageScope || "local") + "Storage"].getItem(t.syncStorageKey + "-" + d),
                    h = p && c[p];
                h && Object.assign(s, {
                    tab: h,
                    panel: document.getElementById(h.getAttribute("aria-controls"))
                }), r(s.tab, !0) || o(s.panel, !1)
            }
        })), c(), s(i, "is-loading", "remove"), window.setTimeout(s.bind(null, i, "is-loaded", "add"), 0), window.addEventListener("hashchange", c)
    }(document.querySelectorAll(".tabs"))
}();
//# sourceMappingURL=/sm/a6b674d2bb82bd28fbe836a547ccea4b083865ad60edc9439db4fa3988c3de3e.map