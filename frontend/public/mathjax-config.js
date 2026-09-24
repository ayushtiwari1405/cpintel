// MathJax settings for Codeforces' $$$...$$$ math. Must load before tex-mml-chtml.js.
// A file rather than an inline script so the Content-Security-Policy can be script-src 'self'.
window.MathJax = {
  tex: { inlineMath: [['$$$', '$$$']], displayMath: [['$$$$$$', '$$$$$$']] },
  options: { skipHtmlTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code'] },
  startup: { typeset: false },
}
