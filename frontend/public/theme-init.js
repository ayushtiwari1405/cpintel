// Applies the saved (or OS) theme before first paint; ThemeContext keeps it in sync after.
// A file rather than an inline script so the Content-Security-Policy can be script-src 'self'.
(function () {
  var t
  try { t = localStorage.getItem('cpintel-theme') } catch (e) {}
  if (t !== 'light' && t !== 'dark') {
    t = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
  }
  if (t === 'dark') document.documentElement.classList.add('dark')
  document.documentElement.style.colorScheme = t
})()
