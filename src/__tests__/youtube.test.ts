import { afterEach, describe, expect, it, vi } from 'vitest'
import { searchYouTube, youtubeVideoFromLink, youtubeVideoId } from '../data/youtube'

const id = 'dQw4w9WgXcQ'

afterEach(() => vi.unstubAllGlobals())

describe('YouTube video links', () => {
  it.each([
    `https://www.youtube.com/watch?v=${id}&list=RD${id}`,
    `https://music.youtube.com/watch?v=${id}`,
    `https://m.youtube.com/shorts/${id}?feature=share`,
    `https://youtu.be/${id}?t=10`,
    `youtube.com/live/${id}`,
    `https://www.youtube-nocookie.com/embed/${id}`,
  ])('accepts supported video URL %s', (url) => {
    expect(youtubeVideoId(`  ${url}  `)).toBe(id)
  })

  it.each([
    '', id, 'a song title', 'https://music.youtube.com/playlist?list=PL123',
    'https://youtube.com/watch?v=short', 'https://youtube.com/watch?v=dQw4w9WgXcQextra',
    'https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ',
    'https://youtube.com@evil.example/watch?v=dQw4w9WgXcQ',
    'https://evil.example/youtube.com/watch?v=dQw4w9WgXcQ',
    'javascript:alert(1)',
  ])('rejects non-video or untrusted URL %s', (url) => {
    expect(youtubeVideoId(url)).toBeNull()
  })

  it('builds a link record using the validated video ID', () => {
    expect(youtubeVideoFromLink(id).thumbnail).toBe(`https://i.ytimg.com/vi/${id}/hqdefault.jpg`)
  })
})

describe('YouTube Data API search', () => {
  it('requests embeddable music videos, safely decodes metadata, and filters invalid or duplicate IDs', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [
      { id: { videoId: id }, snippet: { title: 'Rock &amp; Roll &#39;live&#39;', channelTitle: 'A &quot;channel&quot;' } },
      { id: { videoId: id }, snippet: { title: 'duplicate' } },
      { id: { videoId: 'invalid' }, snippet: { title: 'bad id' } },
      { id: { videoId: 'abcdefghijk' } },
    ] }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()
    const videos = await searchYouTube('  rock & roll  ', ' browser-key ', controller.signal)
    const [url, options] = fetchMock.mock.calls[0] as [URL, RequestInit]
    expect(url.origin + url.pathname).toBe('https://www.googleapis.com/youtube/v3/search')
    expect(Object.fromEntries(url.searchParams.entries())).toMatchObject({ part: 'snippet', type: 'video', videoCategoryId: '10', videoEmbeddable: 'true', q: 'rock & roll', key: 'browser-key' })
    expect(options).toMatchObject({ signal: controller.signal, credentials: 'omit' })
    expect(videos).toEqual([
      { id, title: "Rock & Roll 'live'", channel: 'A "channel"', thumbnail: `https://i.ytimg.com/vi/${id}/hqdefault.jpg` },
      { id: 'abcdefghijk', title: 'YouTube video', channel: 'YouTube', thumbnail: 'https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg' },
    ])
  })

  it('does not fetch without a query or key', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await expect(searchYouTube(' ', 'key')).resolves.toEqual([])
    await expect(searchYouTube('music', '')).rejects.toThrow('configured API key')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('gives useful errors for quota, key restrictions, network and malformed responses without exposing a key', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ error: { errors: [{ reason: 'quotaExceeded' }] } }), { status: 403 }))
      .mockResolvedValueOnce(new Response('{}', { status: 403 }))
      .mockRejectedValueOnce(new TypeError('network failure'))
      .mockResolvedValueOnce(new Response('null', { status: 200 }))
      .mockResolvedValueOnce(new Response('not json', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const key = 'do-not-display-this-key'
    await expect(searchYouTube('x', key)).rejects.toThrow('quota is exhausted')
    await expect(searchYouTube('x', key)).rejects.toThrow('browser API key is valid')
    await expect(searchYouTube('x', key)).rejects.toThrow('Could not reach YouTube')
    await expect(searchYouTube('x', key)).rejects.toThrow('invalid response')
    await expect(searchYouTube('x', key)).rejects.toThrow('invalid response')
  })

  it('preserves abort errors instead of showing a network error', async () => {
    const controller = new AbortController()
    controller.abort()
    const abort = new DOMException('aborted', 'AbortError')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(abort))
    await expect(searchYouTube('x', 'key', controller.signal)).rejects.toBe(abort)
  })
})
