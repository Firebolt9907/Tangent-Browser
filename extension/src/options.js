/* Options page logic: reflect + persist the auto-open setting in browser.storage.local. */
'use strict';
const api = (typeof browser !== 'undefined') ? browser : chrome;
const box = document.getElementById('autoOpen');

api.storage.local.get('autoOpen').then((r) => {
  box.checked = !!(r && r.autoOpen);
}).catch(() => {});

box.addEventListener('change', () => {
  api.storage.local.set({ autoOpen: box.checked });
});
