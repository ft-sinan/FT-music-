export interface YouTubeVideo {
  id: string
  title: string
  channel: string
  thumbnail: string
}

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/
const YOUTUBE_HOSTS = new Set([
  'youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com',
  'youtube-nocookie.com', 'www.youtube-nocookie.com',
])

/** Accept only actual YouTube hosts and video URLs, never arbitrary iframe URLs. */
export function youtubeVideoId(input: string): string | null {
  const text = input.trim()
  if (!text) return null
  const candidate = /^https?:\/\//i.test(text) ? text : /^(?:(?:www|m|music)\.)?(?:youtube\.com|youtube-nocookie\.com|youtu\.be)\//i.test(text) ? `https://${text}` : ''
  if (!candidate) return null
  try {
    const url = new URL(candidate)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null
    const host = url.hostname.toLowerCase()
    let id: string | null = null
    if (host === 'youtu.be' || host === 'www.youtu.be') {
      id = url.pathname.split('/').filter(Boolean)[0] ?? null
    } else if (YOUTUBE_HOSTS.has(host)) {
      const parts = url.pathname.split('/').filter(Boolean)
      if (parts[0] === 'watch') id = url.searchParams.get('v')
      else if (['embed', 'shorts', 'live'].includes(parts[0])) id = parts[1] ?? null
    }
    return id && VIDEO_ID.test(id) ? id : null
  } catch {
    return null
  }
}

export function youtubeVideoFromLink(id: string): YouTubeVideo {
  return {
    id,
    title: 'YouTube video',
    channel: 'Opened from your link',
    thumbnail: `https://i.ytimg.com/vi/${id}/hqdefault.jpg`,
  }
}

function decodeEntities(text: string): string {
  const named: Record<string, string> = { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' }
  return text.replace(/&(#x[\da-f]+|#\d+|amp|quot|apos|lt|gt);/gi, (match, entity: string) => {
    if (entity[0] !== '#') return named[entity.toLowerCase()] ?? match
    const hex = entity[1]?.toLowerCase() === 'x'
    const value = Number.parseInt(entity.slice(hex ? 2 : 1), hex ? 16 : 10)
    return Number.isFinite(value) && value >= 0 && value <= 0x10ffff ? String.fromCodePoint(value) : match
  })
}

export class YouTubeSearchError extends Error {
  constructor(message: string) { super(message); this.name = 'YouTubeSearchError' }
}

/** Search uses the supported YouTube Data API, filtered to embeddable music videos. */
export async function searchYouTube(query: string, apiKey: string, signal?: AbortSignal): Promise<YouTubeVideo[]> {
  const text = query.trim()
  if (!text) return []
  if (!apiKey.trim()) throw new YouTubeSearchError('YouTube search needs a configured API key. You can still paste a video link.')
  const url = new URL('https://www.googleapis.com/youtube/v3/search')
  url.search = new URLSearchParams({
    part: 'snippet', type: 'video', videoCategoryId: '10', videoEmbeddable: 'true',
    maxResults: '20', q: text, key: apiKey.trim(),
  }).toString()

  let response: Response
  try {
    response = await fetch(url, { signal, credentials: 'omit', referrerPolicy: 'strict-origin-when-cross-origin' })
  } catch (error) {
    if (signal?.aborted) throw error
    throw new YouTubeSearchError('Could not reach YouTube. Check your connection and try again.')
  }
  if (!response.ok) {
    let reason = ''
    try {
      const data = await response.json() as { error?: { errors?: Array<{ reason?: string }> } }
      reason = data.error?.errors?.[0]?.reason ?? ''
    } catch { /* Keep errors generic; never display request URLs or API keys. */ }
    if (['quotaExceeded', 'dailyLimitExceeded', 'rateLimitExceeded'].includes(reason)) {
      throw new YouTubeSearchError('The YouTube search quota is exhausted. Try again later or paste a video link.')
    }
    if (response.status === 400 || response.status === 401 || response.status === 403) {
      throw new YouTubeSearchError('YouTube search is unavailable. Check that your browser API key is valid, enabled, and allowed for this site.')
    }
    throw new YouTubeSearchError('YouTube search is temporarily unavailable. Try again later.')
  }

  type Result = { id?: { videoId?: unknown }; snippet?: { title?: unknown; channelTitle?: unknown } }
  let data: { items?: Result[] } | null
  try { data = await response.json() as typeof data } catch { throw new YouTubeSearchError('YouTube returned an invalid response. Try again.') }
  if (!data || !Array.isArray(data.items)) throw new YouTubeSearchError('YouTube returned an invalid response. Try again.')
  const seen = new Set<string>()
  return data.items.flatMap((item) => {
    const id = item?.id?.videoId
    if (typeof id !== 'string' || !VIDEO_ID.test(id) || seen.has(id)) return []
    seen.add(id)
    const title = typeof item.snippet?.title === 'string' ? decodeEntities(item.snippet.title) : 'YouTube video'
    const channel = typeof item.snippet?.channelTitle === 'string' ? decodeEntities(item.snippet.channelTitle) : 'YouTube'
    return [{ id, title, channel, thumbnail: `https://i.ytimg.com/vi/${id}/hqdefault.jpg` }]
  })
}
