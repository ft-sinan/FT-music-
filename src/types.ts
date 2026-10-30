export type Page =
  | { type: 'home' }
  | { type: 'search' }
  | { type: 'youtube' }
  | { type: 'moods' }
  | { type: 'library' }
  | { type: 'liked' }
  | { type: 'recent' }
  | { type: 'queue' }
  | { type: 'about' }
  | { type: 'playlist'; id: string }

export function pageToHash(page: Page): string {
  return page.type === 'home' ? '#/' : page.type === 'playlist' ? `#/playlist/${encodeURIComponent(page.id)}` : `#/${page.type}`
}

export function pageFromHash(hash: string): Page {
  const parts = hash.replace(/^#\/?/, '').split('/')
  if (parts[0] === 'playlist' && parts[1]) {
    try { return { type: 'playlist', id: decodeURIComponent(parts[1]) } } catch { return { type: 'home' } }
  }
  switch (parts[0]) {
    case 'search': case 'youtube': case 'moods': case 'library': case 'liked': case 'recent': case 'queue': case 'about':
      return { type: parts[0] }
    default:
      return { type: 'home' }
  }
}
