// أدوات واجهة صغيرة: عناصر، إشعارات، ونوافذ.
export const $ = (id) => document.getElementById(id);

export function el(tag, attrs = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'style') e.style.cssText = v;
    else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
    else if (k === 'text') e.textContent = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) {
    if (kid === null || kid === undefined || kid === false) continue;
    e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return e;
}

let toastTimer = null;
export function toast(msg, ms = 2600) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), ms);
}

let activeClose = null;

/** نافذة منبثقة. بترجّع {close, root}. */
export function modal(title, body, buttons = [], opts = {}) {
  closeModal();
  const ov = $('overlay');
  ov.innerHTML = '';
  const box = el('div', { class: 'modal' + (opts.wide ? ' wide' : '') });
  if (title) box.append(el('h3', { text: title }));
  box.append(body);
  if (buttons.length) {
    const act = el('div', { class: 'actions' });
    for (const b of buttons) {
      act.append(
        el('button', {
          class: 'btn ' + (b.kind || ''),
          text: b.label,
          onclick: () => {
            const r = b.onClick ? b.onClick() : undefined;
            if (r !== false) closeModal();
          }
        })
      );
    }
    box.append(act);
  }
  ov.append(box);
  ov.hidden = false;
  const onBg = (e) => {
    if (e.target === ov) closeModal();
  };
  ov.onmousedown = onBg;
  activeClose = opts.onClose || null;
  const first = box.querySelector('input[type=text],textarea');
  if (first) setTimeout(() => first.focus(), 30);
  return { root: box, close: closeModal };
}

export function closeModal() {
  const ov = $('overlay');
  if (ov.hidden) return;
  ov.hidden = true;
  ov.innerHTML = '';
  const c = activeClose;
  activeClose = null;
  if (c) c();
}

export function isModalOpen() {
  return !$('overlay').hidden;
}

export function download(name, data, mime) {
  const blob = new Blob([data], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = el('a', { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

export function debounce(fn, ms) {
  let t = null;
  return (...a) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...a), ms);
  };
}
