// Runs in the offscreen document. The service worker asks it to read the
// clipboard (only a document context can), and it replies with the text.

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.target !== 'offscreen' || message.action !== 'read-clipboard') {
    return false;
  }
  readClipboard()
    .then((text) => sendResponse({ ok: true, text }))
    .catch((err: unknown) => sendResponse({ ok: false, error: String(err) }));
  return true; // async response
});

async function readClipboard(): Promise<string> {
  // A textarea + execCommand('paste') is the reliable clipboard read inside an
  // offscreen document (navigator.clipboard.readText requires focus/permission
  // that the offscreen doc lacks).
  const textarea = document.createElement('textarea');
  document.body.appendChild(textarea);
  textarea.focus();
  const ok = document.execCommand('paste');
  const value = textarea.value;
  textarea.remove();
  if (!ok) {
    throw new Error('clipboard read blocked');
  }
  return value;
}
