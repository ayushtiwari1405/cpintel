/**
 * The pinned in-browser toolchains, shared by the Run workers and the code visualiser.
 *
 * Also pinned in the site's Content-Security-Policy (nginx/security-headers.conf); change both.
 */
export const PYODIDE_VERSION = '314.0.7'
export const PYODIDE_BASE = `https://cdn.jsdelivr.net/npm/pyodide@${PYODIDE_VERSION}/`

export const CLANG_VERSION = '22.0.0-git20542-10'
export const CLANG_URL = `https://cdn.jsdelivr.net/npm/@yowasp/clang@${CLANG_VERSION}/gen/bundle.js`
