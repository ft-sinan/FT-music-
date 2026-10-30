/* Cache the app shell for an installed app. Audio remains a network stream. */
const CACHE = 'ft-music-shell-v3'
const scope = new URL(self.registration.scope)
const shellAssets = [
  'manifest.webmanifest', 'favicon.svg', 'icons/icon-192.png', 'icons/icon-512.png',
  'images/hero-signal.webp', 'images/cover-blue-skies.webp', 'images/cover-night-drive.webp',
  'images/cover-slow-mornings.webp', 'images/cover-electric-dreams.webp',
  'images/cover-focus-flow.webp', 'images/cover-good-energy.webp', 'images/cover-after-hours.webp',
]

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE)
    const response = await fetch(scope.href, { cache: 'reload' })
    if (!response.ok) throw new Error('Could not cache the FT Music app shell')
    const html = await response.clone().text()
    await cache.put(scope.href, response)
    // Vite emits hashed entry points; discover them from the built HTML.
    const entryPoints = [...html.matchAll(/(?:src|href)="([^"]+\.(?:js|css))"/g)]
      .map((match) => new URL(match[1], scope).href)
      .filter((url) => new URL(url).origin === scope.origin)
    await Promise.allSettled([...shellAssets.map((path) => new URL(path, scope).href), ...entryPoints].map((url) => cache.add(url)))
    // Cache fonts referenced by the built stylesheet as well as the stylesheet itself.
    for (const stylesheet of entryPoints.filter((url) => url.endsWith('.css'))) {
      const cachedStyle = await cache.match(stylesheet, { ignoreVary: true })
      if (!cachedStyle) continue
      const css = await cachedStyle.text()
      const fonts = [...css.matchAll(/url\(["']?([^"')]+\.woff2)["']?\)/g)]
        .map((match) => new URL(match[1], stylesheet).href)
        .filter((url) => new URL(url).origin === scope.origin)
      await Promise.allSettled(fonts.map((url) => cache.add(url)))
    }
    await self.skipWaiting()
  })())
})

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys()
    await Promise.all(keys.filter((key) => key.startsWith('ft-music-shell-') && key !== CACHE).map((key) => caches.delete(key)))
    await self.clients.claim()
  })())
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  const url = new URL(request.url)
  if (request.method !== 'GET' || url.origin !== scope.origin || !url.pathname.startsWith(scope.pathname) || url.pathname.includes('/audio/') || request.headers.has('range')) return
  // Do not cache future API responses or other potentially personalized data.
  const isStaticAsset = /\.(?:js|css|woff2|webp|png|svg|webmanifest)$/.test(url.pathname)
  if (request.mode !== 'navigate' && !isStaticAsset) return

  event.respondWith((async () => {
    const cache = await caches.open(CACHE)
    try {
      const response = await fetch(request)
      if (response.ok) {
        const key = request.mode === 'navigate' ? scope.href : request
        try { await cache.put(key, response.clone()) } catch { /* A full cache should not block a network response. */ }
      }
      return response
    } catch {
      return (await cache.match(request.mode === 'navigate' ? scope.href : request, { ignoreVary: true })) || Response.error()
    }
  })())
})
