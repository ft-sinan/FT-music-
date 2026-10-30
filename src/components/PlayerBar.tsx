import { ChevronDown, Heart, ListMusic, Pause, Play, Repeat, Repeat1, Shuffle, SkipBack, SkipForward, Volume2, VolumeX } from 'lucide-react'
import type { CSSProperties } from 'react'
import { formatTime } from '../data/catalog'
import type { Player } from '../hooks/useAudioPlayer'

interface Props {
  player: Player
  liked: boolean
  onToggleLike: (id: string) => void
  onOpenQueue: () => void
  onExpand: () => void
}

function Progress({ player }: { player: Player }) {
  const max = Number.isFinite(player.duration) && player.duration > 0 ? player.duration : player.currentTrack.duration
  const value = Math.min(player.elapsed, max)
  return <div className="progress-control">
    <span>{formatTime(value)}</span>
    <input className="range progress-range" type="range" min="0" max={max} step="0.1" value={value} aria-label="Playback position" onChange={(event) => player.seek(Number(event.target.value))} style={{ '--progress': `${max ? value / max * 100 : 0}%` } as CSSProperties} />
    <span>{formatTime(max)}</span>
  </div>
}

function Transport({ player }: { player: Player }) {
  const RepeatIcon = player.repeat === 'one' ? Repeat1 : Repeat
  const repeatLabel = player.repeat === 'off' ? 'Repeat off' : player.repeat === 'all' ? 'Repeat all' : 'Repeat one'
  return <div className="transport-controls">
    <button type="button" className={`icon-button shuffle-button ${player.shuffle ? 'control-active' : ''}`} onClick={() => player.setShuffle(!player.shuffle)} aria-label="Shuffle" aria-pressed={player.shuffle} title={player.shuffle ? 'Turn shuffle off' : 'Turn shuffle on'}><Shuffle size={17} /></button>
    <button type="button" className="icon-button previous-button" onClick={player.previous} aria-label="Previous track" title="Previous track"><SkipBack size={20} fill="currentColor" /></button>
    <button type="button" className="transport-play" onClick={player.togglePlay} aria-label={player.isPlaying ? 'Pause' : 'Play'}>{player.isPlaying ? <Pause size={18} fill="currentColor" /> : <Play size={18} fill="currentColor" />}</button>
    <button type="button" className="icon-button next-button" onClick={() => player.next()} aria-label="Next track" title="Next track"><SkipForward size={20} fill="currentColor" /></button>
    <button type="button" className={`icon-button repeat-button ${player.repeat !== 'off' ? 'control-active' : ''}`} onClick={player.cycleRepeat} aria-label={repeatLabel} title={`${repeatLabel} — click to change`}><RepeatIcon size={18} /></button>
  </div>
}

function Volume({ player }: { player: Player }) {
  const volume = player.muted ? 0 : player.volume
  return <div className="volume-control">
    <button type="button" className="icon-button" onClick={player.toggleMute} aria-label={player.muted || player.volume === 0 ? 'Unmute' : 'Mute'}>{player.muted || player.volume === 0 ? <VolumeX size={19} /> : <Volume2 size={19} />}</button>
    <input className="range volume-range" type="range" min="0" max="1" step="0.01" value={volume} aria-label="Volume" onChange={(event) => player.changeVolume(Number(event.target.value))} style={{ '--progress': `${volume * 100}%` } as CSSProperties} />
  </div>
}

export function PlayerBar({ player, liked, onToggleLike, onOpenQueue, onExpand }: Props) {
  return <footer className="player-bar" aria-label="Music player">
    <div className="mobile-progress" style={{ '--progress': `${player.duration ? Math.min(player.elapsed / player.duration, 1) * 100 : 0}%` } as CSSProperties} />
    <div className="player-track">
      <button type="button" className="player-track-open" onClick={onExpand} aria-label="Open now playing">
        <img src={player.currentTrack.cover} alt="" />
        <span><strong>{player.currentTrack.title}</strong><small>{player.currentTrack.artist}</small></span>
      </button>
      <button type="button" className={`icon-button player-like ${liked ? 'is-liked' : ''}`} onClick={() => onToggleLike(player.currentTrack.id)} aria-label={`${liked ? 'Remove current track from' : 'Add current track to'} liked songs`} aria-pressed={liked}><Heart size={18} fill={liked ? 'currentColor' : 'none'} /></button>
    </div>
    <div className="player-center"><Transport player={player} /><Progress player={player} /></div>
    <div className="player-extras">
      <button type="button" className="icon-button" onClick={onOpenQueue} aria-label="Open queue" title="Open queue"><ListMusic size={20} /></button>
      <Volume player={player} />
    </div>
  </footer>
}

export function ExpandedPlayer({ player, liked, onToggleLike, onOpenQueue, onClose }: Props & { onClose: () => void }) {
  return <div className="expanded-player" role="dialog" aria-modal="true" aria-label="Now playing">
    <div className="expanded-top"><button type="button" className="icon-button" onClick={onClose} aria-label="Close now playing"><ChevronDown size={27} /></button><div><small>NOW PLAYING</small><strong>YOUR LISTENING SPACE</strong></div><button type="button" className="icon-button" onClick={onOpenQueue} aria-label="Open queue"><ListMusic size={21} /></button></div>
    <div className="expanded-content">
      <img className="expanded-cover" src={player.currentTrack.cover} alt={`${player.currentTrack.title} artwork`} />
      <div className="expanded-track"><div><h2>{player.currentTrack.title}</h2><p>{player.currentTrack.artist}</p></div><button type="button" className={`icon-button ${liked ? 'is-liked' : ''}`} onClick={() => onToggleLike(player.currentTrack.id)} aria-label={`${liked ? 'Remove from' : 'Add to'} liked songs`} aria-pressed={liked}><Heart size={23} fill={liked ? 'currentColor' : 'none'} /></button></div>
      <Progress player={player} /><Transport player={player} /><Volume player={player} />
    </div>
  </div>
}
