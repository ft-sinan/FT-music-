// @vitest-environment jsdom
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import App from '../App'
import { YouTubeView } from '../components/YouTube'
import type { YouTubeVideo } from '../data/youtube'

const id = 'dQw4w9WgXcQ'

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
  vi.unstubAllGlobals()
  Reflect.deleteProperty(document, 'visibilityState')
})

function SearchHarness({ apiKey = 'test-browser-key', onPlay = () => {} }: { apiKey?: string; onPlay?: (video: YouTubeVideo) => void }) {
  const [query, setQuery] = useState('')
  return <YouTubeView query={query} setQuery={setQuery} apiKey={apiKey} onPlay={onPlay} />
}

describe('YouTube integration', () => {
  it('pauses local audio and opens and closes pasted video links without an API key', async () => {
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'Play the mix' }))
    await waitFor(() => expect(HTMLMediaElement.prototype.play).toHaveBeenCalled())
    const pauseCalls = vi.mocked(HTMLMediaElement.prototype.pause).mock.calls.length
    fireEvent.click(within(screen.getByRole('navigation', { name: 'Browse' })).getByRole('button', { name: 'YouTube' }))
    expect(window.location.hash).toBe('#/youtube')
    const input = screen.getByLabelText('SONG, ARTIST, OR VIDEO LINK')
    input.focus()
    fireEvent.change(input, { target: { value: `https://music.youtube.com/watch?v=${id}` } })
    fireEvent.click(screen.getByRole('button', { name: 'Search / play' }))
    const modal = screen.getByRole('dialog', { name: 'YouTube video' })
    const iframe = within(modal).getByTitle('YouTube player: YouTube video')
    const src = new URL(iframe.getAttribute('src')!)
    expect(src.origin + src.pathname).toBe(`https://www.youtube-nocookie.com/embed/${id}`)
    expect(src.searchParams.get('autoplay')).toBe('1')
    await waitFor(() => expect(HTMLMediaElement.prototype.pause).toHaveBeenCalledTimes(pauseCalls + 1))
    expect(within(screen.getByRole('contentinfo', { name: 'Music player' })).getByRole('button', { name: 'Play' })).toBeInTheDocument()
    expect(within(modal).getByRole('button', { name: 'Close YouTube player' })).toHaveFocus()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog', { name: 'YouTube video' })).not.toBeInTheDocument()
    expect(input).toHaveFocus()
  })

  it('stops the visible video when the tab is hidden or local audio resumes', async () => {
    window.history.replaceState(null, '', '/#/youtube')
    render(<App />)
    fireEvent.change(screen.getByLabelText('SONG, ARTIST, OR VIDEO LINK'), { target: { value: `https://youtu.be/${id}` } })
    fireEvent.click(screen.getByRole('button', { name: 'Search / play' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    fireEvent(document, new Event('visibilitychange'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    fireEvent.click(screen.getByRole('button', { name: 'Search / play' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('contentinfo', { name: 'Music player' })).getByRole('button', { name: 'Play' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalled()
  })

  it('carries a local search into YouTube and explains the missing-key fallback', () => {
    render(<App />)
    const search = screen.getByRole('searchbox', { name: 'Search songs, artists, playlists' })
    fireEvent.focus(search)
    fireEvent.change(search, { target: { value: 'A favorite song' } })
    fireEvent.click(screen.getByRole('button', { name: /Search YouTube/ }))
    expect(screen.getByLabelText('SONG, ARTIST, OR VIDEO LINK')).toHaveValue('A favorite song')
    fireEvent.click(screen.getByRole('button', { name: 'Search / play' }))
    expect(screen.getByRole('alert')).toHaveTextContent('needs an API key')
    expect(screen.getByRole('link', { name: /Search on YouTube Music/ })).toHaveAttribute('href', 'https://music.youtube.com/search?q=A%20favorite%20song')
  })

  it('rejects playlist-only links without making a search request', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<SearchHarness />)
    fireEvent.change(screen.getByLabelText('SONG, ARTIST, OR VIDEO LINK'), { target: { value: 'https://music.youtube.com/playlist?list=PL123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search / play' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Playlist-only links')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows API results and plays a selected video', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ items: [
      { id: { videoId: id }, snippet: { title: 'A &amp; B', channelTitle: 'Artist' } },
    ] }) }))
    const onPlay = vi.fn()
    render(<SearchHarness onPlay={onPlay} />)
    fireEvent.change(screen.getByLabelText('SONG, ARTIST, OR VIDEO LINK'), { target: { value: 'good music' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search / play' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Play A & B on YouTube' }))
    expect(onPlay).toHaveBeenCalledWith(expect.objectContaining({ id, title: 'A & B', channel: 'Artist' }))
  })

  it('ignores old search results when the query changes', async () => {
    let resolveFetch!: (value: { ok: boolean; json: () => Promise<unknown> }) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise((resolve) => { resolveFetch = resolve })))
    render(<SearchHarness />)
    const input = screen.getByLabelText('SONG, ARTIST, OR VIDEO LINK')
    fireEvent.change(input, { target: { value: 'old search' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search / play' }))
    expect(screen.getByRole('status')).toHaveTextContent('Searching')
    fireEvent.change(input, { target: { value: 'new search' } })
    resolveFetch({ ok: true, json: async () => ({ items: [{ id: { videoId: id }, snippet: { title: 'Stale', channelTitle: 'Artist' } }] }) })
    await waitFor(() => expect(screen.queryByText('Stale')).not.toBeInTheDocument())
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})
