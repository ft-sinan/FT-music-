// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import App from '../App'

beforeEach(() => {
  window.localStorage.clear()
  window.history.replaceState(null, '', '/')
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() })
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(() => Promise.resolve())
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('listening experience', () => {
  it('plays the featured mix and persists liked tracks', async () => {
    const first = render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'Play the mix' }))
    await waitFor(() => expect(HTMLMediaElement.prototype.play).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: 'Add current track to liked songs' }))
    await waitFor(() => expect(JSON.parse(window.localStorage.getItem('ft-music-liked') || '[]')).toEqual(['blue-skies']))
    first.unmount()

    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: /^Liked songs/ }))
    expect(screen.getByRole('heading', { name: /Liked songs/, level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Play Blue Skies' })).toBeInTheDocument()
  })

  it('searches and starts a selected track', async () => {
    const { container } = render(<App />)
    const search = screen.getByRole('searchbox', { name: 'Search songs, artists, playlists' })
    fireEvent.focus(search)
    fireEvent.change(search, { target: { value: 'Night' } })
    expect(screen.getByRole('heading', { name: /Find your next favorite/, level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open Night Drive playlist' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Play Night Life' }))
    await waitFor(() => expect(container.querySelector('audio')).toHaveAttribute('src', '/audio/night-life.mp3'))
  })

  it('creates a playlist and adds a search result to it', async () => {
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: /^Create playlist$/ }))
    fireEvent.change(screen.getByLabelText('PLAYLIST NAME'), { target: { value: 'My mix' } })
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Create playlist' }))
    expect(screen.getByRole('heading', { name: /My mix/, level: 1 })).toBeInTheDocument()

    const search = screen.getByRole('searchbox', { name: 'Search songs, artists, playlists' })
    fireEvent.focus(search)
    fireEvent.change(search, { target: { value: 'Blue Skies' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add Blue Skies to a playlist' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /My mix/ }))
    await waitFor(() => {
      const playlists = JSON.parse(window.localStorage.getItem('ft-music-playlists') || '[]') as Array<{ title: string; trackIds: string[] }>
      expect(playlists[0].title).toBe('My mix')
      expect(playlists[0].trackIds).toEqual(['blue-skies'])
    })

    fireEvent.click(screen.getByRole('button', { name: 'My mix' }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete playlist' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('heading', { name: /My mix/, level: 1 })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete playlist' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete playlist' }))
    await waitFor(() => expect(JSON.parse(window.localStorage.getItem('ft-music-playlists') || 'null')).toEqual([]))
  })
})
