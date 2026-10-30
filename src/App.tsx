import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ArrowLeft, Headphones, Music2, Plus, Search, Trash2, X } from 'lucide-react'
import { playlistById, trackById } from './data/catalog'
import type { UserPlaylist } from './data/catalog'
import type { YouTubeVideo } from './data/youtube'
import { useAudioPlayer } from './hooks/useAudioPlayer'
import { useStoredState } from './hooks/useStoredState'
import { pageFromHash, pageToHash } from './types'
import type { Page } from './types'
import { Brand, MobileNav, Sidebar } from './components/Sidebar'
import { NowPlaying } from './components/NowPlaying'
import { ExpandedPlayer, PlayerBar } from './components/PlayerBar'
import { AboutView, HomeView, LibraryView, MoodsView, PlaylistView, QueueView, SearchView } from './components/Views'
import { YouTubePlayerModal, YouTubeView } from './components/YouTube'

type Dialog = { type: 'create'; trackId?: string } | { type: 'add'; trackId: string } | { type: 'delete'; playlistId: string } | null

const isStringArray = (value: unknown): value is string[] => Array.isArray(value) && value.every((item) => typeof item === 'string')
const isUserPlaylistArray = (value: unknown): value is UserPlaylist[] => Array.isArray(value) && value.every((item: unknown) => {
  if (!item || typeof item !== 'object') return false
  const playlist = item as Record<string, unknown>
  return typeof playlist.id === 'string' && typeof playlist.title === 'string' && typeof playlist.createdAt === 'number' && isStringArray(playlist.trackIds)
})

const pageLabel: Record<Page['type'], string> = {
  home: 'DISCOVER', search: 'SEARCH', youtube: 'YOUTUBE', moods: 'EXPLORE MOODS', library: 'YOUR LIBRARY',
  liked: 'LIKED SONGS', recent: 'RECENTLY PLAYED', playlist: 'PLAYLIST', queue: 'YOUR QUEUE', about: 'ABOUT',
}

function App() {
  const [page, setPage] = useState<Page>(() => pageFromHash(window.location.hash))
  const [query, setQuery] = useState('')
  const [youtubeQuery, setYouTubeQuery] = useState('')
  const [youtubeVideo, setYouTubeVideo] = useState<YouTubeVideo | null>(null)
  const [dialog, setDialog] = useState<Dialog>(null)
  const [expanded, setExpanded] = useState(false)
  const [toast, setToast] = useState('')
  const [likedIds, setLikedIds] = useStoredState<string[]>('ft-music-liked', [], isStringArray)
  const [recentIds, setRecentIds] = useStoredState<string[]>('ft-music-recent', [], isStringArray)
  const [savedIds, setSavedIds] = useStoredState<string[]>('ft-music-saved', [], isStringArray)
  const [playlists, setPlaylists] = useStoredState<UserPlaylist[]>('ft-music-playlists', [], isUserPlaylistArray)
  const player = useAudioPlayer()
  const searchRef = useRef<HTMLInputElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)

  function navigate(target: Page, focusSearch = true) {
    const hash = pageToHash(target)
    if (window.location.hash !== hash) window.history.pushState(null, '', hash)
    setPage(target)
    setExpanded(false)
    contentRef.current?.scrollTo({ top: 0 })
    if (target.type === 'search' && focusSearch) requestAnimationFrame(() => searchRef.current?.focus())
  }

  useEffect(() => {
    const handlePop = () => { setPage(pageFromHash(window.location.hash)); setExpanded(false); setYouTubeVideo(null) }
    window.addEventListener('popstate', handlePop)
    return () => window.removeEventListener('popstate', handlePop)
  }, [])

  useEffect(() => {
    if (!player.isPlaying) return
    const id = player.currentTrack.id
    setRecentIds((previous) => [id, ...previous.filter((item) => item !== id)].slice(0, 30))
  }, [player.currentTrack.id, player.isPlaying, setRecentIds])

  useEffect(() => {
    document.title = youtubeVideo ? `${youtubeVideo.title} · YouTube · FT Music` : player.isPlaying ? `${player.currentTrack.title} · FT Music` : 'FT Music — find your frequency'
  }, [player.isPlaying, player.currentTrack.title, youtubeVideo])

  useEffect(() => {
    // If local playback starts, unmount the YouTube iframe so two sources never play together.
    if (player.isPlaying && youtubeVideo) setYouTubeVideo(null)
  }, [player.isPlaying, youtubeVideo])

  useEffect(() => {
    if (!toast) return
    const timer = window.setTimeout(() => setToast(''), 3000)
    return () => window.clearTimeout(timer)
  }, [toast])

  useEffect(() => {
    if (!player.error) return
    setToast(player.error)
    player.setError('')
  }, [player.error, player.setError])

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        if (youtubeVideo) setYouTubeVideo(null)
        else if (dialog) setDialog(null)
        else if (expanded) setExpanded(false)
        return
      }
      const target = event.target as HTMLElement | null
      const isTyping = target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA' || target?.isContentEditable
      if (!dialog && !expanded && !youtubeVideo && ((event.key === '/' && !isTyping) || ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k'))) {
        event.preventDefault()
        navigate({ type: 'search' })
      } else if (event.code === 'Space' && !isTyping && target?.tagName !== 'BUTTON' && !dialog && !expanded && !youtubeVideo) {
        event.preventDefault()
        player.togglePlay()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  })

  function playYouTube(video: YouTubeVideo) {
    player.pause()
    setExpanded(false)
    setDialog(null)
    setYouTubeVideo(video)
  }

  function toggleLike(id: string) {
    const wasLiked = likedIds.includes(id)
    setLikedIds((previous) => wasLiked ? previous.filter((item) => item !== id) : [...previous, id])
    setToast(wasLiked ? 'Removed from liked songs' : 'Added to liked songs')
  }

  function toggleSaved(id: string) {
    const wasSaved = savedIds.includes(id)
    setSavedIds((previous) => wasSaved ? previous.filter((item) => item !== id) : [...previous, id])
    setToast(wasSaved ? 'Removed from your library' : 'Saved to your library')
  }

  function addToQueue(id: string) {
    player.addToQueue(id)
    setToast(`“${trackById[id].title}” added to your queue`)
  }

  function createPlaylist(name: string, trackId?: string) {
    const id = `user-${typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`}`
    const playlist: UserPlaylist = { id, title: name.trim(), trackIds: trackId ? [trackId] : [], createdAt: Date.now() }
    setPlaylists((previous) => [...previous, playlist])
    setDialog(null)
    setToast(trackId ? `Added to “${playlist.title}”` : `“${playlist.title}” created`)
    if (!trackId) navigate({ type: 'playlist', id })
  }

  function addToPlaylist(playlistId: string, trackId: string) {
    const playlist = playlists.find((item) => item.id === playlistId)
    if (!playlist || playlist.trackIds.includes(trackId)) return
    setPlaylists((previous) => previous.map((item) => item.id === playlistId ? { ...item, trackIds: [...item.trackIds, trackId] } : item))
    setDialog(null)
    setToast(`Added to “${playlist.title}”`)
  }

  function removeFromPlaylist(playlistId: string, trackId: string) {
    setPlaylists((previous) => previous.map((item) => item.id === playlistId ? { ...item, trackIds: item.trackIds.filter((id) => id !== trackId) } : item))
    setToast('Removed from playlist')
  }

  function deletePlaylist(id: string) {
    if (!playlists.some((item) => item.id === id)) return
    setPlaylists((previous) => previous.filter((item) => item.id !== id))
    setDialog(null)
    navigate({ type: 'library' })
    setToast('Playlist deleted')
  }

  const common = { player, likedIds, onToggleLike: toggleLike, onAddToPlaylist: (id: string) => setDialog({ type: 'add', trackId: id }), onAddToQueue: addToQueue, navigate }
  const liked = likedIds.includes(player.currentTrack.id)
  const openQueue = () => navigate({ type: 'queue' })
  const currentPlaylistName = page.type === 'playlist' ? playlistById[page.id]?.title ?? playlists.find((item) => item.id === page.id)?.title : undefined

  return <div className="app-shell">
    <Sidebar page={page} navigate={navigate} playlists={playlists} likedCount={likedIds.length} onCreate={() => setDialog({ type: 'create' })} />
    <div className="workspace" ref={contentRef}>
      <header className="topbar">
        <div className="mobile-brand"><Brand onClick={() => navigate({ type: 'home' })} /></div>
        <div className="breadcrumbs"><span className="breadcrumb-icon">✳</span><span>YOUR SPACE</span><i>/</i><strong>{currentPlaylistName ?? pageLabel[page.type]}</strong></div>
        <div className="topbar-right"><label className={`search-box ${page.type === 'search' ? 'search-box-active' : ''}`}><Search size={18} /><input ref={searchRef} value={query} type="search" placeholder="Search songs, artists, playlists" aria-label="Search songs, artists, playlists" onFocus={() => { if (page.type !== 'search') navigate({ type: 'search' }, false) }} onChange={(event) => setQuery(event.target.value)} /><span className="search-shortcut">/</span></label><button type="button" className="profile-button" aria-label="About FT Music" title="About FT Music" onClick={() => navigate({ type: 'about' })}><Headphones size={18} /></button></div>
      </header>
      <main id="main-content">
        {page.type === 'home' && <HomeView {...common} />}
        {page.type === 'moods' && <MoodsView {...common} />}
        {page.type === 'search' && <SearchView {...common} query={query} clearQuery={() => { setQuery(''); searchRef.current?.focus() }} onSearchYouTube={() => { setYouTubeQuery(query); navigate({ type: 'youtube' }) }} />}
        {page.type === 'youtube' && <YouTubeView query={youtubeQuery} setQuery={setYouTubeQuery} apiKey={String(import.meta.env.VITE_YOUTUBE_API_KEY ?? '').trim()} onPlay={playYouTube} />}
        {page.type === 'library' && <LibraryView {...common} playlists={playlists} savedIds={savedIds} recentIds={recentIds} onCreate={() => setDialog({ type: 'create' })} />}
        {(page.type === 'playlist' || page.type === 'liked' || page.type === 'recent') && <PlaylistView {...common} page={page} playlists={playlists} savedIds={savedIds} recentIds={recentIds} onToggleSaved={toggleSaved} onRemoveFromPlaylist={removeFromPlaylist} onDeletePlaylist={(playlistId) => setDialog({ type: 'delete', playlistId })} />}
        {page.type === 'queue' && <QueueView {...common} />}
        {page.type === 'about' && <AboutView navigate={navigate} />}
      </main>
    </div>
    <NowPlaying player={player} liked={liked} onToggleLike={toggleLike} onOpenQueue={openQueue} />
    <audio ref={player.audioRef} src={player.currentTrack.audio} preload="metadata" onTimeUpdate={player.onTimeUpdate} onLoadedMetadata={player.onLoadedMetadata} onDurationChange={player.onLoadedMetadata} onEnded={() => player.next(true)} onError={player.onAudioError} />
    <PlayerBar player={player} liked={liked} onToggleLike={toggleLike} onOpenQueue={openQueue} onExpand={() => setExpanded(true)} />
    <MobileNav page={page} navigate={navigate} />
    {expanded && <ExpandedPlayer player={player} liked={liked} onToggleLike={toggleLike} onOpenQueue={openQueue} onExpand={() => {}} onClose={() => setExpanded(false)} />}
    {youtubeVideo && <YouTubePlayerModal video={youtubeVideo} onClose={() => setYouTubeVideo(null)} />}
    {dialog && <PlaylistDialog key={`${dialog.type}-${dialog.type === 'delete' ? dialog.playlistId : dialog.trackId ?? ''}`} dialog={dialog} playlists={playlists} onClose={() => setDialog(null)} onCreate={createPlaylist} onAdd={addToPlaylist} onDelete={deletePlaylist} onCreateForTrack={(trackId) => setDialog({ type: 'create', trackId })} />}
    {toast && <div className="toast" role="status">{toast}</div>}
  </div>
}

interface PlaylistDialogProps {
  dialog: Exclude<Dialog, null>
  playlists: UserPlaylist[]
  onClose: () => void
  onCreate: (name: string, trackId?: string) => void
  onAdd: (playlistId: string, trackId: string) => void
  onDelete: (playlistId: string) => void
  onCreateForTrack: (trackId: string) => void
}

function PlaylistDialog({ dialog, playlists, onClose, onCreate, onAdd, onDelete, onCreateForTrack }: PlaylistDialogProps) {
  const [name, setName] = useState('')
  const modalRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const modal = modalRef.current
    if (!modal) return
    if (!modal.contains(document.activeElement)) modal.querySelector<HTMLElement>('button, input')?.focus()
    const trapFocus = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return
      const items = [...modal.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), a[href]')]
      if (!items.length) return
      const first = items[0]
      const last = items[items.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
    modal.addEventListener('keydown', trapFocus)
    return () => modal.removeEventListener('keydown', trapFocus)
  }, [])

  function submit(event: FormEvent) {
    event.preventDefault()
    if (name.trim()) onCreate(name, dialog.type === 'create' ? dialog.trackId : undefined)
  }
  const trackId = dialog.type === 'delete' ? undefined : dialog.trackId
  const track = trackId ? trackById[trackId] : undefined
  const deletingPlaylist = dialog.type === 'delete' ? playlists.find((playlist) => playlist.id === dialog.playlistId) : undefined
  return <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}>
    <div className="modal" ref={modalRef} role="dialog" aria-modal="true" aria-labelledby="modal-title">
      <div className="modal-top"><span className="modal-icon">{dialog.type === 'create' ? <Plus size={21} /> : dialog.type === 'delete' ? <Trash2 size={21} /> : <Music2 size={21} />}</span><button type="button" className="icon-button" onClick={onClose} aria-label="Close dialog"><X size={19} /></button></div>
      {dialog.type === 'create' ? <form onSubmit={submit}><p className="section-kicker">MAKE IT YOURS</p><h2 id="modal-title">Create a playlist<span>.</span></h2><p>Every good mix needs a name.{track ? ` “${track.title}” will be your first track.` : ''}</p><label className="field-label" htmlFor="playlist-name">PLAYLIST NAME</label><input id="playlist-name" className="modal-input" value={name} onChange={(event) => setName(event.target.value)} placeholder="Give your mix a name" maxLength={60} autoFocus /><div className="modal-actions"><button type="button" className="button button-secondary" onClick={onClose}>Cancel</button><button type="submit" className="button button-accent" disabled={!name.trim()}>Create playlist</button></div></form> : dialog.type === 'add' ? <><p className="section-kicker">KEEP THE GOOD ONES CLOSE</p><h2 id="modal-title">Add to playlist<span>.</span></h2><p>Choose a home for “{track?.title}”.</p><div className="modal-playlists">{playlists.length ? playlists.map((playlist) => { const added = playlist.trackIds.includes(dialog.trackId); return <button type="button" key={playlist.id} disabled={added} onClick={() => onAdd(playlist.id, dialog.trackId)}><span className="modal-playlist-art">{playlist.trackIds[0] && trackById[playlist.trackIds[0]] ? <img src={trackById[playlist.trackIds[0]].cover} alt="" /> : <Music2 size={19} />}</span><span><strong>{playlist.title}</strong><small>{added ? 'Already added' : `${playlist.trackIds.length} ${playlist.trackIds.length === 1 ? 'song' : 'songs'}`}</small></span><Plus size={17} /></button> }) : <p className="modal-empty">No playlists yet. Make your first one below.</p>}</div><button type="button" className="button button-accent modal-create" onClick={() => onCreateForTrack(dialog.trackId)}><Plus size={17} /> Create a new playlist</button></> : <><p className="section-kicker">YOUR LIBRARY</p><h2 id="modal-title">Delete playlist<span>?</span></h2><p>“{deletingPlaylist?.title ?? 'This playlist'}” will be permanently removed from this device.</p><div className="modal-actions"><button type="button" className="button button-secondary" onClick={onClose}>Cancel</button><button type="button" className="button button-danger" onClick={() => onDelete(dialog.playlistId)}><Trash2 size={15} /> Delete playlist</button></div></>}
      {dialog.type === 'create' && dialog.trackId && <button className="modal-back-link" type="button" onClick={onClose}><ArrowLeft size={14} /> Back to listening</button>}
    </div>
  </div>
}

export default App
