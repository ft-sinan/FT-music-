import { Heart, ListPlus, Pause, Play, Plus, X } from 'lucide-react'
import type { CuratedPlaylist } from '../data/catalog'
import { formatTime, trackById } from '../data/catalog'
import type { Player } from '../hooks/useAudioPlayer'

interface CollectionCardProps {
  playlist: CuratedPlaylist
  onOpen: (id: string) => void
  onPlay: (ids: string[]) => void
}

export function CollectionCard({ playlist, onOpen, onPlay }: CollectionCardProps) {
  return (
    <article className="collection-card">
      <div className="collection-cover">
        <button type="button" className="collection-image-button" onClick={() => onOpen(playlist.id)} aria-label={`Open ${playlist.title} playlist`}>
          <img src={playlist.cover} alt="" loading="lazy" />
          <span className="collection-badge">FT MIX</span>
        </button>
        <button type="button" className="collection-play" onClick={() => onPlay(playlist.trackIds)} aria-label={`Play ${playlist.title}`} title={`Play ${playlist.title}`}><Play size={18} fill="currentColor" /></button>
      </div>
      <button type="button" className="collection-title" onClick={() => onOpen(playlist.id)}>{playlist.title}</button>
      <p>{playlist.description}</p>
    </article>
  )
}

interface TrackListProps {
  ids: string[]
  player: Player
  likedIds: string[]
  onToggleLike: (id: string) => void
  onAddToPlaylist: (id: string) => void
  onAddToQueue: (id: string) => void
  onRemoveFromPlaylist?: (id: string) => void
  showHeader?: boolean
}

export function TrackList({ ids, player, likedIds, onToggleLike, onAddToPlaylist, onAddToQueue, onRemoveFromPlaylist, showHeader = true }: TrackListProps) {
  const validIds = ids.filter((id) => Boolean(trackById[id]))
  return (
    <div className="track-list">
      {showHeader && <div className="track-table-header"><span>#</span><span>TRACK</span><span>ALBUM</span><span className="track-header-duration">TIME</span><span /></div>}
      {validIds.map((id, index) => {
        const track = trackById[id]
        const active = player.currentTrack.id === id
        const liked = likedIds.includes(id)
        return (
          <div className={`track-row ${active ? 'track-row-active' : ''}`} key={`${id}-${index}`}>
            <button className="track-index" type="button" aria-label={`${active && player.isPlaying ? 'Pause' : 'Play'} ${track.title}`} onClick={() => active ? player.togglePlay() : player.playTracks(validIds, index)}>
              {active && player.isPlaying ? <span className="mini-equalizer" aria-hidden="true"><i /><i /><i /></span> : <><span className="track-number">{String(index + 1).padStart(2, '0')}</span><Play className="track-index-play" size={14} fill="currentColor" /></>}
            </button>
            <button type="button" className="track-main" onClick={() => active ? player.togglePlay() : player.playTracks(validIds, index)}>
              <span className="track-art"><img src={track.cover} alt="" loading="lazy" /><span>{active && player.isPlaying ? <Pause size={14} fill="currentColor" /> : <Play size={14} fill="currentColor" />}</span></span>
              <span className="track-text"><strong>{track.title}</strong><small>{track.artist}</small></span>
            </button>
            <span className="track-album">{track.album}</span>
            <span className="track-duration">{formatTime(track.duration)}</span>
            <div className="track-actions">
              <button type="button" className={`icon-button ${liked ? 'is-liked' : ''}`} aria-label={`${liked ? 'Remove' : 'Add'} ${track.title} ${liked ? 'from' : 'to'} liked songs`} aria-pressed={liked} onClick={() => onToggleLike(id)}><Heart size={16} fill={liked ? 'currentColor' : 'none'} /></button>
              <button type="button" className="icon-button track-queue-action" aria-label={`Add ${track.title} to queue`} title="Add to queue" onClick={() => onAddToQueue(id)}><ListPlus size={17} /></button>
              {onRemoveFromPlaylist ? <button type="button" className="icon-button" aria-label={`Remove ${track.title} from this playlist`} title="Remove from playlist" onClick={() => onRemoveFromPlaylist(id)}><X size={17} /></button> : <button type="button" className="icon-button" aria-label={`Add ${track.title} to a playlist`} title="Add to playlist" onClick={() => onAddToPlaylist(id)}><Plus size={18} /></button>}
            </div>
          </div>
        )
      })}
    </div>
  )
}
