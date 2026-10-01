import background from '../../extension-src/background.js?raw'
import content from '../../extension-src/content.js?raw'
import manifestSource from '../../extension-src/manifest.json?raw'
import { zip } from '@/utils/zip'

/**
 * The CPIntel Codeforces Connector, packaged for whoever is asking.
 *
 * <p>The extension only runs on the one CPIntel site named in its manifest — that is what stops
 * any other site asking it to fetch Codeforces as the user. So it cannot be one fixed download:
 * it has to be stamped with the address of the site handing it out. The page it is downloaded
 * from knows that address exactly, so the zip is put together here, in the browser, and a
 * deployment has nothing to build, host or keep in step.
 */

/** The folder inside the zip, and so the one to pick with "Load unpacked". */
export const EXTENSION_FOLDER = 'cpintel-codeforces'

/** The version this site hands out, as the installed extension reports its own. */
export const EXTENSION_VERSION: string = JSON.parse(manifestSource).version

/** The version of the extension running on this page, or null when there is none. */
export function installedExtensionVersion(): string | null {
  if (typeof document === 'undefined') return null
  return document.documentElement.getAttribute('data-cpintel-cf')
}

/** The extension's files, with this site as the only one it will run on. */
export function extensionZip(origin: string = window.location.origin): Uint8Array {
  const manifest = JSON.parse(manifestSource)
  manifest.content_scripts[0].matches = [`${origin}/*`]

  const text = new TextEncoder()
  return zip([
    { name: `${EXTENSION_FOLDER}/manifest.json`,
      data: text.encode(JSON.stringify(manifest, null, 2) + '\n') },
    { name: `${EXTENSION_FOLDER}/background.js`, data: text.encode(background) },
    { name: `${EXTENSION_FOLDER}/content.js`, data: text.encode(content) },
  ])
}

export function downloadExtension() {
  const blob = new Blob([extensionZip().buffer as ArrayBuffer], { type: 'application/zip' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${EXTENSION_FOLDER}.zip`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
