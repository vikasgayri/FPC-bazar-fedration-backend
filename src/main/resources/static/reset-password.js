'use strict';
const form = document.querySelector('#reset-form');
const status = document.querySelector('#status');
const token = new URLSearchParams(location.hash.slice(1)).get('token');
// Keep the reset credential out of URLs, referrers and browser history after loading.
history.replaceState(null, '', location.pathname);
if (!token) {
  form.hidden = true;
  status.textContent = 'Open the password reset link from your email to continue.';
}
form.addEventListener('submit', async event => {
  event.preventDefault();
  const password = document.querySelector('#password').value;
  if (password !== document.querySelector('#confirm').value) {
    status.textContent = 'The passwords do not match.';
    return;
  }
  const button = form.querySelector('button');
  button.disabled = true;
  status.textContent = 'Saving…';
  try {
    const response = await fetch('/api/auth/reset-password', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, newPassword: password })
    });
    const result = await response.json();
    status.textContent = result.message || 'Unable to reset your password. Please try again.';
    if (response.ok) { form.reset(); form.hidden = true; }
  } catch {
    status.textContent = 'Could not connect. Please try again.';
  } finally { button.disabled = false; }
});
