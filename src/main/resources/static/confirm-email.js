'use strict';
const status = document.querySelector('#status');
const title = document.querySelector('h1');
const token = new URLSearchParams(location.hash.slice(1)).get('token');
// Keep the one-time credential out of URLs, referrers and browser history.
history.replaceState(null, '', location.pathname);
if (!token) {
  title.textContent = 'Open the link from your email';
  status.textContent = 'This page needs the confirmation link that was emailed to you.';
} else {
  fetch('/api/auth/confirm-email', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token })
  }).then(async response => {
    const result = await response.json().catch(() => ({}));
    title.textContent = response.ok ? 'Email confirmed' : 'Link not valid';
    status.textContent = result.message || 'Something went wrong. Please try again.';
  }).catch(() => { status.textContent = 'Could not connect. Please try again.'; });
}
