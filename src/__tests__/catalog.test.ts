import { describe, expect, it } from 'vitest'
import { curatedPlaylists, formatPlaylistLength, formatTime, searchCatalog, trackById, tracks } from '../data/catalog'

describe('catalog', () => {
  it('has unique tracks and valid playlist references', () => {
    expect(new Set(tracks.map((track) => track.id)).size).toBe(tracks.length)
    expect(tracks.length).toBeGreaterThan(0)
    for (const playlist of curatedPlaylists) {
      expect(playlist.trackIds.length).toBeGreaterThan(0)
      for (const id of playlist.trackIds) expect(trackById[id]).toBeDefined()
    }
  })

  it('searches titles, artists, albums, and moods without case sensitivity', () => {
    expect(searchCatalog('  BLUE SKIES ').tracks.map((track) => track.id)).toContain('blue-skies')
    expect(searchCatalog('duru-ai').tracks).toHaveLength(2)
    expect(searchCatalog('retro wave').tracks.map((track) => track.id)).toContain('night-life')
    expect(searchCatalog('focus').playlists.map((playlist) => playlist.id)).toContain('focus-flow')
    expect(searchCatalog('not-a-real-track').tracks).toHaveLength(0)
    expect(searchCatalog('  ').playlists).toHaveLength(0)
  })

  it('formats playback and playlist durations safely', () => {
    expect(formatTime(0)).toBe('0:00')
    expect(formatTime(125.9)).toBe('2:05')
    expect(formatTime(Number.NaN)).toBe('0:00')
    expect(formatPlaylistLength(['blue-skies', 'morning-coffee'])).toBe('6 min')
  })
})
