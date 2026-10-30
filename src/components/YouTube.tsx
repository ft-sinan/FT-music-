import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ArrowUpRight, Link2, Play, Search, X, Youtube } from 'lucide-react'
import { searchYouTube, youtubeVideoFromLink, youtubeVideoId } from '../data/youtube'
import type { YouTubeVideo } from '../data/youtube'

interface YouTubeViewProps {
  query: string
  setQuery: (value: string) => void
  apiKey: string
  onPlay: (video: YouTubeVideo) => void
}

export function YouTubeView({ query, setQuery, apiKey, onPlay }: YouTubeViewProps) {
  const [results, setResults] = useState<YouTubeVideo[]>([])
  const [searched, setSearched] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const searchText = query.trim()
  const youtubeSearchUrl = `https://music.youtube.com/search?q=${encodeURIComponent(searchText)}`

  useEffect(() => () => pending.current?.abort(), [])

  function updateQuery(value: string) {
    pending.current?.abort()
    pending.current = null
    setQuery(value)
    setResults([])
    setSearched(false)
    setLoading(false)
    setError('')
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const text = query.trim()
    if (!text) { setError('Enter a song, artist, or YouTube video link.'); return }
    const id = youtubeVideoId(text)
    if (id) {
      pending.current?.abort()
      setLoading(false)
      setError('')
      onPlay(youtubeVideoFromLink(id))
      return
    }
    if (/^(?:https?:\/\/|(?:(?:www|m|music)\.)?(?:youtube\.com|youtu\.be)\/)/i.test(text)) {
      setError('Paste a valid YouTube video link. Playlist-only links can’t be played here.')
      return
    }
    if (!apiKey) {
      setError('In-app search needs an API key. You can search on YouTube Music and paste a video link instead.')
      return
    }
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    setLoading(true)
    setError('')
    setSearched(false)
    setResults([])
    try {
      const videos = await searchYouTube(text, apiKey, controller.signal)
      if (!controller.signal.aborted) { setResults(videos); setSearched(true) }
    } catch (caught) {
      if (!controller.signal.aborted) setError(caught instanceof Error ? caught.message : 'Could not search YouTube. Try again.')
    } finally {
      if (!controller.signal.aborted) { setLoading(false); pending.current = null }
    }
  }

  return <div className="view inner-view youtube-view">
    <div className="page-intro youtube-intro"><p className="section-kicker"><span className="tiny-spark">✳</span> EXPLORE YOUTUBE</p><h1>More music, from YouTube<span>.</span></h1><p>Search embeddable music videos, or paste a YouTube or YouTube Music video link.</p></div>
    <div className="youtube-search-card">
      <div className="youtube-card-heading"><span className="youtube-mark"><Youtube size={23} /></span><div><strong>Find something to play</strong><p>Music videos, powered by YouTube.</p></div></div>
      <form onSubmit={submit} className="youtube-form"><label htmlFor="youtube-query">SONG, ARTIST, OR VIDEO LINK</label><div className="youtube-input-row"><span className="youtube-input-icon"><Search size={18} /></span><input id="youtube-query" type="text" value={query} onChange={(event) => updateQuery(event.target.value)} placeholder="Search for a song or paste a video link" autoComplete="off" /><button type="submit" className="button button-accent" disabled={loading}>{loading ? 'Searching…' : 'Search / play'}</button></div></form>
      {error && <p className="youtube-error" role="alert">{error}</p>}
      {!apiKey && <div className="youtube-setup-note"><Link2 size={17} /><div><strong>Paste a video link to play—no key needed.</strong><p>To search inside the app, configure a YouTube Data API key. Or search on YouTube Music, copy a video link, and paste it above.</p><details className="youtube-setup-help"><summary>How to set up in-app search</summary><p>Enable YouTube Data API v3 in Google Cloud and create an API key restricted to that API and your website origins. Add it as <code>VITE_YOUTUBE_API_KEY</code> in <code>.env.local</code>, then restart the app. Browser keys are visible to visitors, so restrictions are essential.</p><a href="https://developers.google.com/youtube/v3/getting-started" target="_blank" rel="noopener noreferrer">YouTube API setup guide <ArrowUpRight size={12} /></a></details></div></div>}
      <a className="youtube-external-link" href={searchText ? youtubeSearchUrl : 'https://music.youtube.com/'} target="_blank" rel="noopener noreferrer">Search on YouTube Music <ArrowUpRight size={14} /></a>
    </div>
    {loading && <div className="youtube-status" role="status">Searching YouTube…</div>}
    {searched && <section className="content-section youtube-results"><div className="section-heading"><div><p className="section-kicker">FROM YOUTUBE</p><h2>Music videos<span className="heading-period">.</span></h2><p className="section-description">{results.length} {results.length === 1 ? 'result' : 'results'} for “{searchText}”</p></div></div>
      {results.length ? <div className="youtube-result-grid">{results.map((video) => <article className="youtube-result" key={video.id}><button className="youtube-result-image" type="button" onClick={() => onPlay(video)} aria-label={`Play ${video.title} on YouTube`}><img src={video.thumbnail} alt="" loading="lazy" /><span><Play size={19} fill="currentColor" /></span></button><button type="button" className="youtube-result-title" onClick={() => onPlay(video)}>{video.title}</button><p>{video.channel}</p></article>)}</div> : <div className="empty-state compact-empty"><span className="empty-icon"><Search size={25} /></span><h2>No embeddable videos found</h2><p>Try a different search or paste a video link.</p></div>}
    </section>}
    <div className="youtube-disclosure"><Youtube size={17} /><p>Videos play in YouTube’s visible player. Availability, ads, and buffering are controlled by YouTube. Videos stop when the player closes or the tab is hidden and can’t be added to FT Music playlists or the local queue. This is not a YouTube Music account or an audio-only integration.</p></div>
  </div>
}

export function YouTubePlayerModal({ video, onClose }: { video: YouTubeVideo; onClose: () => void }) {
  const modalRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const closeHandler = useRef(onClose)
  closeHandler.current = onClose
  useEffect(() => {
    const previouslyFocused = document.activeElement
    closeRef.current?.focus()
    // Don't keep YouTube audio playing after the visible player is no longer on screen.
    const stopWhenHidden = () => { if (document.visibilityState === 'hidden') closeHandler.current() }
    const trapFocus = (event: KeyboardEvent) => {
      if (event.key !== 'Tab' || !modalRef.current) return
      const items = [...modalRef.current.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], iframe')]
      if (!items.length) return
      const first = items[0]
      const last = items[items.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('visibilitychange', stopWhenHidden)
    document.addEventListener('keydown', trapFocus)
    return () => {
      document.removeEventListener('visibilitychange', stopWhenHidden)
      document.removeEventListener('keydown', trapFocus)
      if (previouslyFocused instanceof HTMLElement && previouslyFocused.isConnected) previouslyFocused.focus()
    }
  }, [])

  const params = new URLSearchParams({ autoplay: '1', playsinline: '1', rel: '0', origin: window.location.origin })
  const embedUrl = `https://www.youtube-nocookie.com/embed/${encodeURIComponent(video.id)}?${params.toString()}`
  const watchUrl = `https://www.youtube.com/watch?v=${encodeURIComponent(video.id)}`
  return <div className="modal-backdrop youtube-player-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}>
    <div ref={modalRef} className="youtube-player-modal" role="dialog" aria-modal="true" aria-labelledby="youtube-player-title">
      <div className="youtube-player-header"><div><span><Youtube size={16} /> PLAYING ON YOUTUBE</span><h2 id="youtube-player-title">{video.title}</h2><p>{video.channel}</p></div><button ref={closeRef} type="button" className="icon-button" onClick={onClose} aria-label="Close YouTube player"><X size={21} /></button></div>
      <iframe className="youtube-embed" src={embedUrl} title={`YouTube player: ${video.title}`} allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowFullScreen referrerPolicy="strict-origin-when-cross-origin" />
      <div className="youtube-player-footer"><p>If playback doesn’t start, press play in the video. Some videos may not allow embedding.</p><a href={watchUrl} target="_blank" rel="noopener noreferrer" onClick={onClose}>Open on YouTube <ArrowUpRight size={14} /></a></div>
      <p className="youtube-player-note">Closing this player or switching tabs stops playback. YouTube may show ads.</p>
    </div>
  </div>
}
