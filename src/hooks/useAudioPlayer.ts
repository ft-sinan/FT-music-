import { useEffect, useRef, useState } from 'react'
import type { SyntheticEvent } from 'react'
import { playlistById, trackById } from '../data/catalog'
import { useStoredState } from './useStoredState'

export type RepeatMode = 'off' | 'all' | 'one'

function shuffled<T>(items: T[]): T[] {
  const result = [...items]
  for (let i = result.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[result[i], result[j]] = [result[j], result[i]]
  }
  return result
}

// Keep the actual queue in playback order. This makes "up next" accurate even in shuffle mode.
function restoreOrder(original: string[], remaining: string[]): string[] {
  const counts = new Map<string, number>()
  for (const id of remaining) counts.set(id, (counts.get(id) ?? 0) + 1)
  const restored: string[] = []
  for (const id of [...original, ...remaining]) {
    const count = counts.get(id) ?? 0
    if (count > 0) {
      restored.push(id)
      counts.set(id, count - 1)
    }
  }
  return restored
}

export function useAudioPlayer() {
  const audioRef = useRef<HTMLAudioElement>(null)
  const originalUpcoming = useRef<string[]>([])
  const [queue, setQueue] = useState<string[]>(() => [...playlistById['daily-mix'].trackIds])
  const [queueIndex, setQueueIndex] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [duration, setDuration] = useState(trackById[queue[0]].duration)
  const [shuffle, setShuffleState] = useState(false)
  const [repeat, setRepeat] = useState<RepeatMode>('off')
  const [volume, setVolume] = useStoredState('ft-music-volume', 0.7, (value): value is number =>
    typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1,
  )
  const [muted, setMuted] = useState(false)
  const [error, setError] = useState('')
  const currentTrack = trackById[queue[queueIndex]] ?? trackById[queue[0]]

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    audio.volume = volume
    audio.muted = muted
  }, [volume, muted])

  // The <audio> element's src is updated in the same commit as currentTrack.
  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    let cancelled = false
    if (isPlaying) {
      audio.play().catch((playError: unknown) => {
        // A rapid change of track can abort the previous play request harmlessly.
        if (cancelled || (playError instanceof DOMException && playError.name === 'AbortError')) return
        setIsPlaying(false)
        setError('Could not start playback. Please try again.')
      })
    } else {
      audio.pause()
    }
    return () => { cancelled = true }
  }, [currentTrack.id, isPlaying])

  function startTracks(ids: string[], startIndex: number, shouldShuffle: boolean) {
    const validIds = ids.filter((id) => Boolean(trackById[id]))
    if (!validIds.length) return
    const selected = ids[startIndex]
    const validIndex = Math.max(0, validIds.indexOf(selected))
    const selectedId = validIds[validIndex]
    let nextQueue = validIds
    let nextIndex = validIndex
    if (shouldShuffle) {
      originalUpcoming.current = validIds.filter((_, index) => index !== validIndex)
      nextQueue = [selectedId, ...shuffled(originalUpcoming.current)]
      nextIndex = 0
    } else {
      originalUpcoming.current = []
    }
    if (selectedId === currentTrack.id && audioRef.current) {
      audioRef.current.currentTime = 0
      if (isPlaying && audioRef.current.paused) void audioRef.current.play().catch(() => setIsPlaying(false))
    }
    setQueue(nextQueue)
    setQueueIndex(nextIndex)
    setElapsed(0)
    setDuration(trackById[selectedId].duration)
    setError('')
    setIsPlaying(true)
  }

  function playTracks(ids: string[], startIndex = 0) {
    startTracks(ids, startIndex, shuffle)
  }

  function playShuffled(ids: string[]) {
    if (!ids.length) return
    setShuffleState(true)
    startTracks(ids, Math.floor(Math.random() * ids.length), true)
  }

  function setShuffle(enabled: boolean) {
    if (enabled === shuffle) return
    const played = queue.slice(0, queueIndex + 1)
    const remaining = queue.slice(queueIndex + 1)
    if (enabled) {
      originalUpcoming.current = [...remaining]
      setQueue([...played, ...shuffled(remaining)])
    } else {
      setQueue([...played, ...restoreOrder(originalUpcoming.current, remaining)])
      originalUpcoming.current = []
    }
    setShuffleState(enabled)
  }

  function playQueueIndex(index: number) {
    if (index < 0 || index >= queue.length) return
    if (queue[index] === currentTrack.id && audioRef.current) {
      audioRef.current.currentTime = 0
      // When a single-track queue repeats, the src doesn't change.
      if (audioRef.current.paused) void audioRef.current.play().catch(() => setIsPlaying(false))
    }
    setQueueIndex(index)
    setElapsed(0)
    setDuration(trackById[queue[index]].duration)
    setError('')
    setIsPlaying(true)
  }

  function togglePlay() {
    setError('')
    setIsPlaying((playing) => !playing)
  }

  function pause() {
    setIsPlaying(false)
  }

  function next(fromEnded = false) {
    const audio = audioRef.current
    if (fromEnded && repeat === 'one' && audio) {
      audio.currentTime = 0
      setElapsed(0)
      void audio.play().catch(() => setIsPlaying(false))
      return
    }
    let nextIndex = queueIndex + 1
    if (nextIndex >= queue.length) {
      if (repeat === 'all') nextIndex = 0
      else {
        if (fromEnded) setIsPlaying(false)
        return
      }
    }
    playQueueIndex(nextIndex)
  }

  function previous() {
    const audio = audioRef.current
    if (audio && audio.currentTime > 3) {
      audio.currentTime = 0
      setElapsed(0)
    } else if (queueIndex > 0) {
      playQueueIndex(queueIndex - 1)
    } else if (audio) {
      audio.currentTime = 0
      setElapsed(0)
    }
  }

  function seek(seconds: number) {
    const audio = audioRef.current
    if (!audio || !Number.isFinite(seconds)) return
    const nextTime = Math.max(0, Math.min(seconds, audio.duration || duration))
    audio.currentTime = nextTime
    setElapsed(nextTime)
  }

  function addToQueue(id: string) {
    if (trackById[id]) setQueue((previousQueue) => [...previousQueue, id])
  }

  function removeFromQueue(index: number) {
    if (index <= queueIndex || index >= queue.length) return
    setQueue((previousQueue) => previousQueue.filter((_, itemIndex) => itemIndex !== index))
  }

  function clearUpcoming() {
    setQueue((previousQueue) => previousQueue.slice(0, queueIndex + 1))
  }

  function cycleRepeat() {
    setRepeat((mode) => mode === 'off' ? 'all' : mode === 'all' ? 'one' : 'off')
  }

  function changeVolume(value: number) {
    setVolume(Math.max(0, Math.min(1, value)))
    if (value > 0) setMuted(false)
  }

  function toggleMute() {
    if (muted || volume === 0) {
      if (volume === 0) setVolume(0.7)
      setMuted(false)
    } else {
      setMuted(true)
    }
  }

  function onTimeUpdate(event: SyntheticEvent<HTMLAudioElement>) {
    setElapsed(event.currentTarget.currentTime)
  }

  function onLoadedMetadata(event: SyntheticEvent<HTMLAudioElement>) {
    const value = event.currentTarget.duration
    if (Number.isFinite(value)) setDuration(value)
  }

  function onAudioError() {
    setIsPlaying(false)
    setError('This track could not be loaded. Try another one.')
  }

  // Expose the active track and transport to lock screens and browser media controls.
  useEffect(() => {
    if (!('mediaSession' in navigator)) return
    const session = navigator.mediaSession
    if (typeof MediaMetadata !== 'undefined') {
      session.metadata = new MediaMetadata({
        title: currentTrack.title,
        artist: currentTrack.artist,
        album: currentTrack.album,
        artwork: [{ src: new URL(currentTrack.cover, window.location.href).href, sizes: '800x800', type: 'image/webp' }],
      })
    }
    session.playbackState = isPlaying ? 'playing' : 'paused'
    const handlers: Array<[MediaSessionAction, MediaSessionActionHandler]> = [
      ['play', () => setIsPlaying(true)],
      ['pause', () => setIsPlaying(false)],
      ['previoustrack', previous],
      ['nexttrack', () => next()],
      ['seekbackward', (details) => seek((audioRef.current?.currentTime ?? 0) - (details.seekOffset ?? 10))],
      ['seekforward', (details) => seek((audioRef.current?.currentTime ?? 0) + (details.seekOffset ?? 10))],
      ['seekto', (details) => { if (details.seekTime !== undefined) seek(details.seekTime) }],
    ]
    for (const [action, handler] of handlers) {
      try { session.setActionHandler(action, handler) } catch { /* Not every browser supports every action. */ }
    }
    return () => {
      for (const [action] of handlers) {
        try { session.setActionHandler(action, null) } catch { /* Ignore unsupported actions. */ }
      }
    }
  }, [currentTrack.id, isPlaying, queue, queueIndex, repeat, duration])

  return {
    audioRef, queue, queueIndex, currentTrack, isPlaying, elapsed, duration, shuffle, repeat,
    volume, muted, error, setError, playTracks, playShuffled, playQueueIndex, togglePlay, pause, next, previous,
    seek, addToQueue, removeFromQueue, clearUpcoming, setShuffle, cycleRepeat, changeVolume, toggleMute,
    onTimeUpdate, onLoadedMetadata, onAudioError,
  }
}

export type Player = ReturnType<typeof useAudioPlayer>
