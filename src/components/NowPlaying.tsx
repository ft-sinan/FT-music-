import { ArrowRight, Heart, ListMusic, Play } from 'lucide-react'
import { trackById } from '../data/catalog'
import type { Player } from '../hooks/useAudioPlayer'

interface Props {
  player: Player
  liked: boolean
  onToggleLike: (id: string) => void
  onOpenQueue: () => void
}

export function NowPlaying({ player, liked, onToggleLike, onOpenQueue }: Props) {
  const { currentTrack } = player
  const upcoming = player.queue.slice(player.queueIndex + 1, player.queueIndex + 4)
  return (
    <aside className="now-panel" aria-label="Now playing and queue">
      <div className="now-panel-heading"><span>NOW PLAYING</span><button type="button" className="icon-button" onClick={onOpenQueue} aria-label="Open play queue" title="Open queue"><ListMusic size={19} /></button></div>
      <div className="now-cover-wrap"><img className="now-cover" src={currentTrack.cover} alt={`${currentTrack.title} artwork`} /><span className="now-cover-shine" /></div>
      <div className="now-track-heading">
        <div><h2>{currentTrack.title}</h2><p>{currentTrack.artist}</p></div>
        <button type="button" className={`icon-button now-like ${liked ? 'is-liked' : ''}`} onClick={() => onToggleLike(currentTrack.id)} aria-label={`${liked ? 'Remove from' : 'Add to'} liked songs`} aria-pressed={liked}><Heart size={20} fill={liked ? 'currentColor' : 'none'} /></button>
      </div>
      <div className="playing-source"><span className="source-dot" /> YOUR LISTENING SPACE <span>✳</span></div>
      <div className="queue-heading"><div><p className="mini-kicker">KEEP THE MUSIC GOING</p><h3>Up next <span>↗</span></h3></div><button type="button" onClick={onOpenQueue}>View queue <ArrowRight size={14} /></button></div>
      <div className="upcoming-list">
        {upcoming.length ? upcoming.map((id, offset) => {
          const track = trackById[id]
          return <button className="upcoming-track" type="button" key={`${id}-${offset}`} onClick={() => player.playQueueIndex(player.queueIndex + offset + 1)} aria-label={`Play ${track.title}`}>
            <img src={track.cover} alt="" loading="lazy" /><span><strong>{track.title}</strong><small>{track.artist}</small></span><Play className="upcoming-play" size={14} fill="currentColor" />
          </button>
        }) : <p className="queue-empty">Nothing queued up next. Find something you love and keep listening.</p>}
      </div>
      <div className="now-panel-footer"><span>✳</span> GOOD MUSIC, GOOD MOMENTS <span>✳</span></div>
    </aside>
  )
}
