import { useMemo, useState } from 'react'
import { ArrowDownRight, ArrowRight, Check, Clock3, Disc3, Headphones, Heart, ListMusic, Music2, Play, Plus, Search, Shuffle, Sparkles, Trash2, X, Youtube } from 'lucide-react'
import { asset, curatedPlaylists, formatPlaylistLength, formatTime, moods, playlistById, searchCatalog, trackById, tracks } from '../data/catalog'
import type { Mood, UserPlaylist } from '../data/catalog'
import type { Player } from '../hooks/useAudioPlayer'
import type { Page } from '../types'
import { CollectionCard, TrackList } from './Catalog'

interface CommonProps {
  player: Player
  likedIds: string[]
  onToggleLike: (id: string) => void
  onAddToPlaylist: (id: string) => void
  onAddToQueue: (id: string) => void
  navigate: (page: Page) => void
}

function SectionHeading({ kicker, title, description, action, onAction }: { kicker?: string; title: string; description?: string; action?: string; onAction?: () => void }) {
  return <div className="section-heading">
    <div>{kicker && <p className="section-kicker">{kicker}</p>}<h2>{title}<span className="heading-period">.</span></h2>{description && <p className="section-description">{description}</p>}</div>
    {action && onAction && <button type="button" className="text-link" onClick={onAction}>{action} <ArrowRight size={15} /></button>}
  </div>
}

export function HomeView(props: CommonProps) {
  const [mood, setMood] = useState<'All' | Mood>('All')
  const selections = curatedPlaylists.filter((playlist) => playlist.id !== 'daily-mix' && (mood === 'All' || playlist.moods.includes(mood)))
  const trackIds = tracks.filter((track) => mood === 'All' || track.moods.includes(mood)).slice(0, 5).map((track) => track.id)
  const openPlaylist = (id: string) => props.navigate({ type: 'playlist', id })
  return <div className="view home-view">
    <div className="welcome-heading"><div><p className="section-kicker"><span className="tiny-spark">✳</span> YOUR SPACE TO LISTEN</p><h1>Find your frequency<span>.</span></h1><p>Good music for wherever the day takes you.</p></div><div className="welcome-stamp"><span>GOOD MUSIC</span><strong>GOOD MOOD <span>✳</span></strong></div></div>
    <section className="hero-card" aria-label="Featured playlist" style={{ backgroundImage: `linear-gradient(90deg, rgba(10,12,10,.92) 0%, rgba(10,12,10,.7) 42%, rgba(10,12,10,.08) 100%), url('${asset('images/hero-signal.webp')}')` }}>
      <div className="hero-content"><p className="hero-kicker"><span className="hero-kicker-mark">✳</span> THE FT EDIT <span className="hero-kicker-line" /> VOL. 01</p><h2>Music for<br /><em>the moment.</em></h2><p>A little something for wherever you're headed.</p><div className="hero-actions"><button type="button" className="button button-accent" onClick={() => props.player.playTracks(playlistById['daily-mix'].trackIds)}><Play size={15} fill="currentColor" /> Play the mix</button><button type="button" className="hero-link" onClick={() => openPlaylist('daily-mix')}>Explore the mix <ArrowRight size={15} /></button></div></div>
      <div className="hero-corner"><span>MADE FOR LISTENING</span><strong>01 <span>/ 01</span></strong></div>
    </section>
    <div className="mood-filter" aria-label="Filter by mood"><span className="mood-label">PICK YOUR MOOD <ArrowDownRight size={14} /></span><div className="mood-chips">{moods.map((item) => <button type="button" key={item} className={`mood-chip ${mood === item ? 'selected' : ''}`} onClick={() => setMood(item)} aria-pressed={mood === item}>{item}</button>)}</div></div>
    <section className="content-section collection-section"><SectionHeading kicker="A LITTLE SOMETHING FOR YOU" title="Made for your moment" description="The right sounds for right now." action="Explore all" onAction={() => props.navigate({ type: 'moods' })} /><div className="collection-grid">{selections.slice(0, 5).map((playlist) => <CollectionCard key={playlist.id} playlist={playlist} onOpen={openPlaylist} onPlay={props.player.playTracks} />)}</div></section>
    <section className="content-section tracks-section"><SectionHeading kicker="PRESS PLAY ON SOMETHING GOOD" title={mood === 'All' ? 'Worth a listen' : `${mood} sounds`} description="A few tracks we think you'll love." action="See all tracks" onAction={() => props.navigate({ type: 'search' })} /><TrackList {...props} ids={trackIds} /></section>
    <div className="end-note"><span>✳</span> LISTEN A LITTLE CLOSER <span>✳</span></div>
  </div>
}

export function MoodsView(props: CommonProps) {
  const [mood, setMood] = useState<'All' | Mood>('All')
  const selections = curatedPlaylists.filter((playlist) => playlist.id !== 'daily-mix' && (mood === 'All' || playlist.moods.includes(mood)))
  return <div className="view inner-view">
    <div className="page-intro"><p className="section-kicker"><span className="tiny-spark">✳</span> FIND YOUR SOUND</p><h1>A mood for every moment<span>.</span></h1><p>Whatever your day sounds like, start here.</p></div>
    <div className="mood-filter page-mood-filter"><span className="mood-label">FILTER BY MOOD <ArrowDownRight size={14} /></span><div className="mood-chips">{moods.map((item) => <button type="button" key={item} className={`mood-chip ${mood === item ? 'selected' : ''}`} onClick={() => setMood(item)} aria-pressed={mood === item}>{item}</button>)}</div></div>
    <section className="content-section"><SectionHeading kicker="CURATED BY FT MUSIC" title="Find your mix" description={`${selections.length} playlists for wherever you are.`} /><div className="collection-grid all-collections">{selections.map((playlist) => <CollectionCard key={playlist.id} playlist={playlist} onOpen={(id) => props.navigate({ type: 'playlist', id })} onPlay={props.player.playTracks} />)}</div></section>
  </div>
}

export function SearchView(props: CommonProps & { query: string; clearQuery: () => void; onSearchYouTube: () => void }) {
  const results = useMemo(() => searchCatalog(props.query), [props.query])
  const hasQuery = props.query.trim().length > 0
  const hasResults = results.tracks.length > 0 || results.playlists.length > 0
  return <div className="view inner-view search-view">
    <div className="page-intro"><p className="section-kicker"><span className="tiny-spark">✳</span> SOMETHING NEW AWAITS</p><h1>Find your next favorite<span>.</span></h1><p>Search songs, artists, albums, and playlists in our collection.</p></div>
    <div className="youtube-search-prompt"><Youtube size={19} /><span>Looking for music videos?</span><button type="button" onClick={props.onSearchYouTube}>Search YouTube <ArrowRight size={14} /></button></div>
    {!hasQuery ? <><div className="search-prompt"><span><Search size={21} /></span><div><strong>Looking for something?</strong><p>Type in the search bar above to get started.</p></div></div><section className="content-section"><SectionHeading kicker="NOT SURE WHERE TO START?" title="Browse by mood" description="Let the music find you." /><div className="collection-grid all-collections">{curatedPlaylists.slice(1, 6).map((playlist) => <CollectionCard key={playlist.id} playlist={playlist} onOpen={(id) => props.navigate({ type: 'playlist', id })} onPlay={props.player.playTracks} />)}</div></section><section className="content-section"><SectionHeading kicker="THE FT MUSIC COLLECTION" title="All tracks" description="Every track, ready when you are." /><TrackList {...props} ids={tracks.map((track) => track.id)} /></section></> : !hasResults ? <div className="empty-state"><span className="empty-icon"><Search size={27} /></span><h2>No matches for “{props.query.trim()}”</h2><p>Try another song, artist, album, or mood.</p><button type="button" className="button button-secondary" onClick={props.clearQuery}>Clear search</button></div> : <>
      {results.tracks.length > 0 && <section className="content-section search-results"><SectionHeading kicker={`${results.tracks.length} ${results.tracks.length === 1 ? 'TRACK' : 'TRACKS'} FOUND`} title="Songs" /><TrackList {...props} ids={results.tracks.map((track) => track.id)} /></section>}
      {results.playlists.length > 0 && <section className="content-section"><SectionHeading kicker={`${results.playlists.length} ${results.playlists.length === 1 ? 'MIX' : 'MIXES'} FOUND`} title="Playlists" /><div className="collection-grid all-collections">{results.playlists.map((playlist) => <CollectionCard key={playlist.id} playlist={playlist} onOpen={(id) => props.navigate({ type: 'playlist', id })} onPlay={props.player.playTracks} />)}</div></section>}
    </>}
  </div>
}

interface LibraryProps extends CommonProps {
  playlists: UserPlaylist[]
  savedIds: string[]
  recentIds: string[]
  onCreate: () => void
}

export function LibraryView({ navigate, playlists, savedIds, likedIds, recentIds, onCreate }: LibraryProps) {
  const saved = savedIds.map((id) => playlistById[id]).filter(Boolean)
  return <div className="view inner-view">
    <div className="page-intro page-intro-with-action"><div><p className="section-kicker"><span className="tiny-spark">✳</span> YOUR MUSIC, ALL IN ONE PLACE</p><h1>Made yours<span>.</span></h1><p>All the sounds you've saved along the way.</p></div><button type="button" className="button button-accent" onClick={onCreate}><Plus size={17} /> Create playlist</button></div>
    <section className="content-section"><SectionHeading kicker="YOUR COLLECTION" title="Your library" description="A little collection of what you love." /><div className="library-grid">
      <button type="button" className="library-tile" onClick={() => navigate({ type: 'liked' })}><span className="library-cover library-liked"><Heart size={38} fill="currentColor" /></span><strong>Liked songs</strong><small>{likedIds.length} {likedIds.length === 1 ? 'song' : 'songs'} · Your collection</small></button>
      <button type="button" className="library-tile" onClick={() => navigate({ type: 'recent' })}><span className="library-cover library-recent"><Clock3 size={38} /></span><strong>Recently played</strong><small>{recentIds.length} {recentIds.length === 1 ? 'song' : 'songs'} · Listening history</small></button>
      {playlists.map((playlist) => <button type="button" key={playlist.id} className="library-tile" onClick={() => navigate({ type: 'playlist', id: playlist.id })}><span className="library-cover library-user">{playlist.trackIds[0] && trackById[playlist.trackIds[0]] ? <img src={trackById[playlist.trackIds[0]].cover} alt="" /> : <Music2 size={38} />}</span><strong>{playlist.title}</strong><small>{playlist.trackIds.length} {playlist.trackIds.length === 1 ? 'song' : 'songs'} · Your playlist</small></button>)}
      {saved.map((playlist) => <button type="button" key={playlist.id} className="library-tile" onClick={() => navigate({ type: 'playlist', id: playlist.id })}><span className="library-cover"><img src={playlist.cover} alt="" /></span><strong>{playlist.title}</strong><small>Playlist · FT Music</small></button>)}
      <button type="button" className="library-tile create-tile" onClick={onCreate}><span className="library-cover library-create"><Plus size={34} /></span><strong>Create a playlist</strong><small>Make a mix of your own</small></button>
    </div></section>
  </div>
}

interface PlaylistViewProps extends CommonProps {
  page: Page
  playlists: UserPlaylist[]
  savedIds: string[]
  recentIds: string[]
  onToggleSaved: (id: string) => void
  onRemoveFromPlaylist: (playlistId: string, trackId: string) => void
  onDeletePlaylist: (id: string) => void
}

export function PlaylistView(props: PlaylistViewProps) {
  const playlistId = props.page.type === 'playlist' ? props.page.id : undefined
  const curated = playlistId ? playlistById[playlistId] : undefined
  const userPlaylist = playlistId ? props.playlists.find((playlist) => playlist.id === playlistId) : undefined
  const isLiked = props.page.type === 'liked'
  const isRecent = props.page.type === 'recent'
  if (!curated && !userPlaylist && !isLiked && !isRecent) return <div className="view inner-view"><div className="empty-state"><span className="empty-icon"><Music2 size={28} /></span><h2>Playlist not found</h2><p>This playlist might have been removed.</p><button type="button" className="button button-secondary" onClick={() => props.navigate({ type: 'library' })}>Go to your library</button></div></div>
  const title = curated?.title ?? userPlaylist?.title ?? (isLiked ? 'Liked songs' : 'Recently played')
  const ids = (curated?.trackIds ?? userPlaylist?.trackIds ?? (isLiked ? props.likedIds : props.recentIds)).filter((id) => Boolean(trackById[id]))
  const description = curated?.description ?? (isLiked ? 'The songs you keep coming back to.' : isRecent ? 'A little trip down listening lane.' : 'A mix made by you, for you.')
  const cover = curated?.cover ?? (userPlaylist?.trackIds[0] ? trackById[userPlaylist.trackIds[0]]?.cover : undefined)
  const saved = curated ? props.savedIds.includes(curated.id) : false
  return <div className="view inner-view playlist-view">
    <div className="playlist-hero">
      <div className={`playlist-hero-cover ${isLiked ? 'library-liked' : isRecent ? 'library-recent' : 'library-user'}`}>{cover ? <img src={cover} alt="" /> : isLiked ? <Heart size={54} fill="currentColor" /> : isRecent ? <Clock3 size={54} /> : <Music2 size={54} />}</div>
      <div className="playlist-hero-copy"><p className="section-kicker">{curated ? curated.eyebrow : userPlaylist ? 'YOUR PLAYLIST' : 'YOUR COLLECTION'}</p><h1>{title}<span>.</span></h1><p>{description}</p><div className="playlist-meta"><span className="mini-brand">FT</span><strong>{userPlaylist || isLiked || isRecent ? 'Your library' : 'FT Music'}</strong><span>•</span><span>{ids.length} {ids.length === 1 ? 'song' : 'songs'}</span>{ids.length > 0 && <><span>•</span><span>{formatPlaylistLength(ids)}</span></>}</div></div>
    </div>
    <div className="playlist-actions"><button type="button" className="button button-accent" disabled={!ids.length} onClick={() => props.player.playTracks(ids)}><Play size={16} fill="currentColor" /> Play</button><button type="button" className="button button-secondary" disabled={!ids.length} onClick={() => props.player.playShuffled(ids)}><Shuffle size={16} /> Shuffle</button>{curated && <button type="button" className="button button-quiet" onClick={() => props.onToggleSaved(curated.id)}>{saved ? <Check size={17} /> : <Plus size={17} />}{saved ? 'Saved to library' : 'Save playlist'}</button>}{userPlaylist && <button type="button" className="button button-quiet delete-playlist" onClick={() => props.onDeletePlaylist(userPlaylist.id)}><Trash2 size={16} /> Delete playlist</button>}</div>
    {ids.length ? <section className="content-section playlist-tracks"><SectionHeading kicker="THE TRACKLIST" title="Settle in and listen" /><TrackList {...props} ids={ids} onRemoveFromPlaylist={userPlaylist ? (id) => props.onRemoveFromPlaylist(userPlaylist.id, id) : undefined} /></section> : <div className="empty-state compact-empty"><span className="empty-icon">{isLiked ? <Heart size={26} /> : <Music2 size={26} />}</span><h2>{isLiked ? 'Your favorites live here' : isRecent ? 'The music starts with you' : 'Your playlist is waiting'}</h2><p>{isLiked ? 'Tap the heart on a track to save it for later.' : isRecent ? 'The songs you play will show up here.' : 'Add songs using the plus button next to any track.'}</p><button type="button" className="button button-secondary" onClick={() => props.navigate({ type: 'home' })}>Discover music <ArrowRight size={15} /></button></div>}
  </div>
}

export function QueueView({ player, onToggleLike, likedIds, navigate }: CommonProps) {
  const upcoming = player.queue.slice(player.queueIndex + 1)
  const current = player.currentTrack
  return <div className="view inner-view queue-view"><div className="page-intro"><p className="section-kicker"><span className="tiny-spark">✳</span> KEEP THE MUSIC GOING</p><h1>Your queue<span>.</span></h1><p>{player.shuffle ? 'Shuffle is on. Here’s your listening order.' : 'Here’s what’s playing and what’s coming up next.'}</p></div>
    <section className="content-section"><SectionHeading kicker="RIGHT NOW" title="Now playing" /><div className="queue-row current-queue-row"><img src={current.cover} alt="" /><div><strong>{current.title}</strong><small>{current.artist}</small></div><span>{formatTime(current.duration)}</span><button type="button" className={`icon-button ${likedIds.includes(current.id) ? 'is-liked' : ''}`} onClick={() => onToggleLike(current.id)} aria-label={`${likedIds.includes(current.id) ? 'Remove from' : 'Add to'} liked songs`}><Heart size={17} fill={likedIds.includes(current.id) ? 'currentColor' : 'none'} /></button></div></section>
    <section className="content-section"><SectionHeading kicker={`${upcoming.length} ${upcoming.length === 1 ? 'TRACK' : 'TRACKS'} IN LINE`} title="Up next" action={upcoming.length ? 'Clear upcoming' : undefined} onAction={player.clearUpcoming} />{upcoming.length ? <div className="queue-list">{upcoming.map((id, offset) => { const track = trackById[id]; return <div className="queue-row" key={`${id}-${offset}`}><button type="button" className="queue-row-play" aria-label={`Play ${track.title}`} onClick={() => player.playQueueIndex(player.queueIndex + offset + 1)}><img src={track.cover} alt="" /><span className="queue-row-overlay"><Play size={15} fill="currentColor" /></span></button><div><strong>{track.title}</strong><small>{track.artist}</small></div><span>{formatTime(track.duration)}</span><button type="button" className="icon-button" aria-label={`Remove ${track.title} from queue`} onClick={() => player.removeFromQueue(player.queueIndex + offset + 1)}><X size={17} /></button></div> })}</div> : <div className="empty-state compact-empty"><span className="empty-icon"><ListMusic size={26} /></span><h2>The queue is all yours</h2><p>Add a song to your queue or start a new mix.</p><button type="button" className="button button-secondary" onClick={() => navigate({ type: 'home' })}>Find something to play <ArrowRight size={15} /></button></div>}</section>
  </div>
}

export function AboutView({ navigate }: { navigate: (page: Page) => void }) {
  return <div className="view inner-view about-view"><div className="page-intro"><p className="section-kicker"><span className="tiny-spark">✳</span> A NOTE FROM US</p><h1>For the love of listening<span>.</span></h1><p>A little home for good music and the moments that go with it.</p></div><div className="about-card"><span className="about-icon"><Headphones size={30} /></span><h2>Good music should be easy to find.</h2><p>FT Music is a simple, personal listening space. Discover a mix, make it your own, and keep your favorite sounds close.</p><p>This starter collection contains full-length public-domain (CC0) tracks by <strong>HoliznaCC0</strong> and <strong>DURU-AI</strong>. The YouTube section uses YouTube’s video player and, when configured, the YouTube Data API for search. It is not a YouTube Music account or audio-only service.</p><div className="about-links"><a href="https://github.com/seyedpouramir/alvana-audio" target="_blank" rel="noreferrer">HoliznaCC0 tracks via Alvana <ArrowRight size={15} /></a><a href="https://github.com/uncle-sheepsky/duru-ai-cc0-bgm" target="_blank" rel="noreferrer">DURU-AI tracks <ArrowRight size={15} /></a><a href="https://creativecommons.org/publicdomain/zero/1.0/" target="_blank" rel="noreferrer">About CC0 <ArrowRight size={15} /></a></div><button type="button" className="button button-accent" onClick={() => navigate({ type: 'home' })}><Disc3 size={16} /> Back to the music</button></div><div className="about-signoff"><Sparkles size={15} /> MADE FOR MUSIC LOVERS, EVERYWHERE.</div></div>
}
