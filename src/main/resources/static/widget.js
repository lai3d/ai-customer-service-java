/*
 * The embeddable chat widget (docs/widget.md). One script tag on the tenant's page:
 *
 *   <script src="https://<host>/widget.js" data-key="cs_..." async></script>
 *
 * data-key is a widget key issued in the operations admin, usable only from the origins
 * it lists; data-host defaults to the script's own origin; data-lang is en or zh (default:
 * the browser's); data-title and data-greeting override the text; data-position is
 * "right" or "left". On a page where the customer is signed in to the tenant's panel,
 * data-customer-token (or data-customer-token-key, a localStorage key holding it) is
 * forwarded as X-Customer-Token, so the assistant can read that customer's own account.
 * Everything renders inside a shadow root, so the page's styles and the widget's never
 * meet, and every piece of text is a text node, never markup.
 *
 * It shows `message` and `error` events and breaks a paragraph at a `tool` event, the seam
 * between a turn's two model calls (see docs/reliability.md); retrieval, tool and usage
 * detail is the demo page's business, not a customer's.
 */
(function () {
  "use strict";
  var script = document.currentScript;
  if (!script) return;
  var key = script.dataset.key;
  if (!key) { console.error("[ai-customer-service] widget.js needs data-key"); return; }
  var host = script.dataset.host || new URL(script.src, location.href).origin;
  var lang = (script.dataset.lang || navigator.language || "en").toLowerCase().indexOf("zh") === 0 ? "zh" : "en";
  var side = script.dataset.position === "left" ? "left" : "right";
  function customerToken() {
    if (script.dataset.customerToken) return script.dataset.customerToken;
    if (script.dataset.customerTokenKey) {
      try { return localStorage.getItem(script.dataset.customerTokenKey) || ""; } catch (e) { return ""; }
    }
    return "";
  }
  var TEXT = {
    en: { title: "Customer service", greeting: "Hi! Ask me about orders, returns, shipping or payments.",
          placeholder: "Type a message…", send: "Send", open: "Open chat", close: "Close chat", reset: "New conversation",
          busy: "I'm still answering your last message; one moment.", interrupted: "The assistant was interrupted. Please try again.",
          unavailable: "The assistant is unavailable right now. Please try again in a minute.",
          budget: "This conversation has reached its limit. Please start a new one.",
          misconfigured: "This chat is not set up for this site yet." },
    zh: { title: "在线客服", greeting: "你好！可以问我订单、退货、物流或付款的问题。",
          placeholder: "输入消息…", send: "发送", open: "打开对话", close: "关闭对话", reset: "新会话",
          busy: "上一条还在回复中，请稍等。", interrupted: "回复中断了，请重试。",
          unavailable: "客服暂时不可用，请稍后再试。", budget: "这个会话已达到上限，请开始新会话。",
          misconfigured: "这个站点的在线客服还没有配置好。" }
  };
  var t = TEXT[lang];
  var title = script.dataset.title || t.title;
  var greeting = script.dataset.greeting || t.greeting;
  var storageKey = "ai-cs-widget:" + key.slice(3, 11) + ":conversation";
  var conversationId = null;
  try { conversationId = localStorage.getItem(storageKey); } catch (e) { /* storage may be unavailable */ }

  var root = document.createElement("div");
  root.setAttribute("data-ai-customer-service", "");
  var shadow = root.attachShadow({ mode: "open" });
  var style = document.createElement("style");
  style.textContent = [
    ":host{all:initial}",
    ".launcher{position:fixed;bottom:20px;" + side + ":20px;width:56px;height:56px;border-radius:28px;border:0;background:#2563eb;color:#fff;",
    "font:600 14px system-ui,sans-serif;cursor:pointer;box-shadow:0 6px 20px rgba(0,0,0,.2);z-index:2147483000}",
    ".launcher:focus-visible{outline:3px solid #93c5fd}",
    ".panel{position:fixed;bottom:88px;" + side + ":20px;width:360px;max-width:calc(100vw - 40px);height:520px;max-height:calc(100vh - 110px);",
    "display:none;flex-direction:column;background:#fff;color:#111;border-radius:14px;box-shadow:0 12px 40px rgba(0,0,0,.25);",
    "font:14px/1.45 system-ui,-apple-system,'Segoe UI',Roboto,'PingFang SC','Noto Sans CJK SC',sans-serif;overflow:hidden;z-index:2147483000}",
    ".panel.open{display:flex}",
    ".head{display:flex;align-items:center;gap:8px;padding:12px 14px;background:#2563eb;color:#fff}",
    ".head strong{flex:1;font-size:15px}",
    ".head button{background:transparent;border:0;color:#fff;cursor:pointer;font:inherit;font-size:13px;opacity:.9;padding:4px 6px;border-radius:6px}",
    ".head button:hover{background:rgba(255,255,255,.15)}",
    ".log{flex:1;overflow-y:auto;padding:14px;display:flex;flex-direction:column;gap:10px;background:#f6f7f9}",
    ".msg{max-width:85%;padding:9px 12px;border-radius:12px;white-space:pre-wrap;word-wrap:break-word}",
    ".msg.bot{align-self:flex-start;background:#fff;border:1px solid #e5e7eb;border-bottom-left-radius:4px}",
    ".msg.user{align-self:flex-end;background:#2563eb;color:#fff;border-bottom-right-radius:4px}",
    ".msg.error{align-self:flex-start;background:#fef2f2;color:#991b1b;border:1px solid #fecaca}",
    ".msg.typing:after{content:'…';animation:blink 1s infinite}",
    "@keyframes blink{50%{opacity:.3}}",
    "form{display:flex;gap:8px;padding:10px;border-top:1px solid #e5e7eb;background:#fff}",
    "input{flex:1;padding:10px 12px;border:1px solid #d1d5db;border-radius:10px;font:inherit;color:#111;background:#fff}",
    "input:focus{outline:2px solid #93c5fd;border-color:#2563eb}",
    "form button{padding:0 14px;border:0;border-radius:10px;background:#2563eb;color:#fff;font:inherit;cursor:pointer}",
    "form button:disabled{opacity:.5;cursor:default}",
    ".foot{font-size:11px;color:#6b7280;text-align:center;padding:0 0 8px;background:#fff}"
  ].join("");
  shadow.appendChild(style);

  var launcher = el("button", "launcher");
  launcher.type = "button";
  launcher.textContent = "💬";
  launcher.setAttribute("aria-label", t.open);
  var panel = el("div", "panel");
  panel.setAttribute("role", "dialog");
  panel.setAttribute("aria-label", title);
  var head = el("div", "head");
  var heading = document.createElement("strong");
  heading.textContent = title;
  var reset = el("button", "");
  reset.type = "button";
  reset.textContent = t.reset;
  var close = el("button", "");
  close.type = "button";
  close.textContent = "✕";
  close.setAttribute("aria-label", t.close);
  head.appendChild(heading); head.appendChild(reset); head.appendChild(close);
  var log = el("div", "log");
  log.setAttribute("aria-live", "polite");
  var form = document.createElement("form");
  var input = document.createElement("input");
  input.type = "text";
  input.maxLength = 8000;
  input.placeholder = t.placeholder;
  input.setAttribute("aria-label", t.placeholder);
  var send = document.createElement("button");
  send.type = "submit";
  send.textContent = t.send;
  form.appendChild(input); form.appendChild(send);
  var foot = el("div", "foot");
  foot.textContent = "AI";
  panel.appendChild(head); panel.appendChild(log); panel.appendChild(form); panel.appendChild(foot);
  shadow.appendChild(launcher);
  shadow.appendChild(panel);
  (document.body || document.documentElement).appendChild(root);

  var open = false;
  var busy = false;
  var inFlight = null;
  bubble("bot", greeting);

  launcher.onclick = function () { setOpen(!open); };
  close.onclick = function () { setOpen(false); };
  panel.addEventListener("keydown", function (e) { if (e.key === "Escape") setOpen(false); });
  reset.onclick = function () {
    if (inFlight) inFlight.abort();
    inFlight = null; busy = false; send.disabled = false;
    conversationId = null;
    try { localStorage.removeItem(storageKey); } catch (e) { /* ignore */ }
    while (log.firstChild) log.removeChild(log.firstChild);
    bubble("bot", greeting);
    input.focus();
  };
  form.onsubmit = function (e) { e.preventDefault(); ask(input.value); };

  function setOpen(value) {
    open = value;
    panel.classList.toggle("open", open);
    launcher.setAttribute("aria-label", open ? t.close : t.open);
    launcher.setAttribute("aria-expanded", String(open));
    if (open) input.focus();
  }

  function el(tag, cls) { var e = document.createElement(tag); if (cls) e.className = cls; return e; }

  function bubble(kind, text) {
    var m = el("div", "msg " + kind);
    m.textContent = text;
    log.appendChild(m);
    log.scrollTop = log.scrollHeight;
    return m;
  }

  function ask(question) {
    question = (question || "").trim();
    if (busy || !question) return;
    busy = true; send.disabled = true; input.value = "";
    bubble("user", question);
    var answer = bubble("bot", "");
    answer.classList.add("typing");
    var text = "";
    var textSinceTool = false;
    var controller = new AbortController();
    inFlight = controller;
    var headers = { "Content-Type": "application/json", "Accept": "text/event-stream", "Authorization": "Bearer " + key };
    var token = customerToken();
    if (token) headers["X-Customer-Token"] = token;
    fetch(host + "/api/v1/chat/stream", {
      method: "POST", headers: headers, signal: controller.signal, mode: "cors",
      body: JSON.stringify({ conversationId: conversationId, message: question })
    }).then(function (response) {
      var id = response.headers.get("X-Conversation-Id");
      if (id) { conversationId = id; try { localStorage.setItem(storageKey, id); } catch (e) { /* ignore */ } }
      if (!response.ok || !response.body) {
        answer.classList.remove("typing");
        answer.classList.add("error");
        answer.textContent = response.status === 401 || response.status === 403 ? t.misconfigured
          : response.status === 409 ? t.busy
          : response.status === 429 ? t.budget
          : t.unavailable;
        return;
      }
      return readEvents(response, function (name, payload) {
        if (name === "message") {
          var chunk = payload.text || "";
          if (chunk && !textSinceTool && text) text += "\n\n";
          if (chunk) textSinceTool = true;
          text += chunk;
          answer.textContent = text;
          log.scrollTop = log.scrollHeight;
        } else if (name === "tool") {
          textSinceTool = false;
        } else if (name === "error") {
          answer.classList.add("error");
          answer.textContent = text ? text + "\n\n" + t.interrupted : t.interrupted;
        }
      });
    }).catch(function (e) {
      if (e && e.name === "AbortError") return;
      answer.classList.add("error");
      answer.textContent = t.unavailable;
    }).then(function () {
      answer.classList.remove("typing");
      if (inFlight === controller) { inFlight = null; busy = false; send.disabled = false; input.focus(); }
    });
  }

  // Server-sent events by hand: EventSource cannot POST or send a header.
  function readEvents(response, onEvent) {
    var reader = response.body.getReader();
    var decoder = new TextDecoder();
    var buffer = "";
    function pump() {
      return reader.read().then(function (r) {
        if (r.done) return;
        buffer += decoder.decode(r.value, { stream: true });
        var split;
        while ((split = buffer.indexOf("\n\n")) >= 0) {
          var frame = buffer.slice(0, split);
          buffer = buffer.slice(split + 2);
          var name = "message", data = "";
          frame.split("\n").forEach(function (line) {
            if (line.indexOf("event:") === 0) name = line.slice(6).trim();
            else if (line.indexOf("data:") === 0) data += line.slice(5).replace(/^ /, "");
          });
          if (!data) continue;
          var payload;
          try { payload = JSON.parse(data); } catch (e) { payload = { text: data }; }
          onEvent(name, payload);
        }
        return pump();
      });
    }
    return pump();
  }
})();
