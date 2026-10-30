import { Clock3, Compass, Heart, Home, Library, Plus, Search, ArrowUpRight, Music2, Youtube } from 'lucide-react'
import type { UserPlaylist } from '../data/catalog'
import type { Page } from '../types'

interface Props {
  page: Page
  navigate: (page: Page) => void
  playlists: UserPlaylist[]
  likedCount: number
  onCreate: () => void
}

export function Brand({ onClick }: { onClick: () => void }) {
  return (
    <button className="brand" type="button" onClick={onClick} aria-label="FT Music home">
      <span className="brand-mark" aria-hidden="true"><i /><i /><i /><i /></span>
      <span className="brand-name">FT<span>music</span><small>LISTEN A LITTLE CLOSER</small></span>
    </button>
  )
}

export function Sidebar({ page, navigate, playlists, likedCount, onCreate }: Props) {
  const nav = [
    { label: 'Discover', icon: Home, page: { type: 'home' } as Page },
    { label: 'Search', icon: Search, page: { type: 'search' } as Page },
    { label: 'YouTube', icon: Youtube, page: { type: 'youtube' } as Page },
    { label: 'Explore moods', icon: Compass, page: { type: 'moods' } as Page },
  ]
  return (
    <aside className="sidebar" aria-label="Main navigation">
      <Brand onClick={() => navigate({ type: 'home' })} />
      <div className="sidebar-scroll">
        <p className="sidebar-label">MENU</p>
        <nav className="side-nav" aria-label="Browse">
          {nav.map(({ label, icon: Icon, page: target }) => (
            <button key={label} className={`side-link ${page.type === target.type ? 'active' : ''}`} type="button" onClick={() => navigate(target)} aria-current={page.type === target.type ? 'page' : undefined}>
              <Icon size={19} strokeWidth={1.8} /><span>{label}</span>
            </button>
          ))}
        </nav>
        <div className="side-section-heading">
          <p className="sidebar-label">YOUR LIBRARY</p>
          <button className="icon-button sidebar-plus" type="button" aria-label="Create playlist" title="Create playlist" onClick={onCreate}><Plus size={17} /></button>
        </div>
        <nav className="side-nav" aria-label="Your music">
          <button className={`side-link ${page.type === 'library' ? 'active' : ''}`} type="button" onClick={() => navigate({ type: 'library' })} aria-current={page.type === 'library' ? 'page' : undefined}>
            <Library size={19} strokeWidth={1.8} /><span>Your library</span>
          </button>
          <button className={`side-link ${page.type === 'liked' ? 'active' : ''}`} type="button" onClick={() => navigate({ type: 'liked' })} aria-current={page.type === 'liked' ? 'page' : undefined}>
            <Heart size={19} strokeWidth={1.8} /><span>Liked songs</span>{likedCount > 0 && <span className="side-count">{likedCount}</span>}
          </button>
          <button className={`side-link ${page.type === 'recent' ? 'active' : ''}`} type="button" onClick={() => navigate({ type: 'recent' })} aria-current={page.type === 'recent' ? 'page' : undefined}>
            <Clock3 size={19} strokeWidth={1.8} /><span>Recently played</span>
          </button>
          {playlists.map((playlist) => (
            <button key={playlist.id} className={`side-link user-playlist-link ${page.type === 'playlist' && page.id === playlist.id ? 'active' : ''}`} type="button" onClick={() => navigate({ type: 'playlist', id: playlist.id })}>
              <Music2 size={18} strokeWidth={1.8} /><span>{playlist.title}</span>
            </button>
          ))}
        </nav>
        <button className="create-side" type="button" onClick={onCreate}><span><Plus size={16} /></span> Create a playlist</button>
      </div>
      <div className="sidebar-footer">
        <div className="sidebar-note-icon"><Music2 size={18} /></div>
        <strong>Made for music lovers.</strong>
        <p>A little space for sounds that stay with you.</p>
        <button type="button" onClick={() => navigate({ type: 'about' })}>About the music <ArrowUpRight size={13} /></button>
      </div>
    </aside>
  )
}

export function MobileNav({ page, navigate }: Pick<Props, 'page' | 'navigate'>) {
  const nav = [
    { label: 'Discover', icon: Home, page: { type: 'home' } as Page },
    { label: 'Search', icon: Search, page: { type: 'search' } as Page },
    { label: 'YouTube', icon: Youtube, page: { type: 'youtube' } as Page },
    { label: 'Moods', icon: Compass, page: { type: 'moods' } as Page },
    { label: 'Library', icon: Library, page: { type: 'library' } as Page },
  ]
  return <nav className="mobile-nav" aria-label="Mobile navigation">{nav.map(({ label, icon: Icon, page: target }) => (
    <button key={label} type="button" className={page.type === target.type || (target.type === 'library' && ['liked', 'recent', 'playlist'].includes(page.type)) ? 'active' : ''} aria-current={page.type === target.type || (target.type === 'library' && ['liked', 'recent', 'playlist'].includes(page.type)) ? 'page' : undefined} onClick={() => navigate(target)}>
      <Icon size={20} strokeWidth={1.8} /><span>{label}</span>
    </button>
  ))}</nav>
}
