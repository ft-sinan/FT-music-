export type Mood = 'Chill' | 'Focus' | 'Electronic' | 'Feel good'

export interface Track {
  id: string
  title: string
  artist: string
  album: string
  duration: number
  cover: string
  audio: string
  moods: Mood[]
}

export interface CuratedPlaylist {
  id: string
  title: string
  description: string
  cover: string
  moods: Mood[]
  trackIds: string[]
  eyebrow: string
}

export interface UserPlaylist {
  id: string
  title: string
  trackIds: string[]
  createdAt: number
}

export const asset = (path: string) => `${import.meta.env.BASE_URL}${path}`

// The bundled recordings are public-domain/CC0. See README for original sources.
export const tracks: Track[] = [
  {
    id: 'blue-skies', title: 'Blue Skies', artist: 'HoliznaCC0', album: 'Happy Lo-Fi',
    duration: 176, cover: asset('images/cover-blue-skies.webp'), audio: asset('audio/blue-skies.mp3'),
    moods: ['Chill', 'Focus', 'Feel good'],
  },
  {
    id: 'city-in-the-rearview', title: 'City In The Rearview', artist: 'HoliznaCC0', album: 'Chill Beats',
    duration: 176, cover: asset('images/cover-night-drive.webp'), audio: asset('audio/city-in-the-rearview.mp3'),
    moods: ['Chill', 'Focus'],
  },
  {
    id: 'morning-coffee', title: 'Morning Coffee', artist: 'HoliznaCC0', album: 'Lo-Fi And Chill',
    duration: 192, cover: asset('images/cover-slow-mornings.webp'), audio: asset('audio/morning-coffee.mp3'),
    moods: ['Chill', 'Focus'],
  },
  {
    id: 'day-dreams', title: 'Day Dreams', artist: 'HoliznaCC0', album: 'Retro Wave',
    duration: 198, cover: asset('images/cover-electric-dreams.webp'), audio: asset('audio/day-dreams.mp3'),
    moods: ['Electronic', 'Feel good'],
  },
  {
    id: 'deja-vu', title: 'Deja Vu', artist: 'HoliznaCC0', album: 'Chillout Instrumentals',
    duration: 125, cover: asset('images/cover-after-hours.webp'), audio: asset('audio/deja-vu.mp3'),
    moods: ['Chill', 'Focus'],
  },
  {
    id: 'night-life', title: 'Night Life', artist: 'HoliznaCC0', album: 'Retro Wave',
    duration: 224, cover: asset('images/cover-electric-dreams.webp'), audio: asset('audio/night-life.mp3'),
    moods: ['Electronic'],
  },
  {
    id: 'breath', title: 'Breath', artist: 'HoliznaCC0', album: 'Funk',
    duration: 151, cover: asset('images/cover-good-energy.webp'), audio: asset('audio/breath.mp3'),
    moods: ['Feel good'],
  },
  {
    id: 'happy-dance', title: 'Happy Dance', artist: 'HoliznaCC0', album: 'Happy Pop Electronic',
    duration: 156, cover: asset('images/cover-good-energy.webp'), audio: asset('audio/happy-dance.mp3'),
    moods: ['Feel good', 'Electronic'],
  },
  {
    id: 'duru-roomscene-lofi', title: 'Roomscene Lo-fi', artist: 'DURU-AI', album: 'Roomscene Lo-fi',
    duration: 104, cover: asset('images/cover-focus-flow.webp'), audio: asset('audio/duru-roomscene-lofi.mp3'),
    moods: ['Chill', 'Focus'],
  },
  {
    id: 'duru-rhythm-fever', title: 'Rhythm Fever', artist: 'DURU-AI', album: 'Rhythm Fever',
    duration: 89, cover: asset('images/cover-after-hours.webp'), audio: asset('audio/duru-rhythm-fever.mp3'),
    moods: ['Electronic', 'Feel good'],
  },
]

export const trackById: Record<string, Track> = Object.fromEntries(tracks.map((track) => [track.id, track]))

export const curatedPlaylists: CuratedPlaylist[] = [
  {
    id: 'daily-mix', title: 'The Feel Good Mix', eyebrow: 'THE FT EDIT',
    description: 'A little bit of everything, picked for wherever the day takes you.',
    cover: asset('images/cover-blue-skies.webp'), moods: ['Chill', 'Focus', 'Electronic', 'Feel good'],
    trackIds: ['blue-skies', 'city-in-the-rearview', 'morning-coffee', 'day-dreams', 'deja-vu', 'night-life', 'breath', 'happy-dance', 'duru-roomscene-lofi', 'duru-rhythm-fever'],
  },
  {
    id: 'night-drive', title: 'Night Drive', eyebrow: 'AFTER DARK',
    description: 'For taking the long way home.',
    cover: asset('images/cover-night-drive.webp'), moods: ['Chill', 'Electronic'],
    trackIds: ['city-in-the-rearview', 'night-life', 'day-dreams', 'deja-vu', 'duru-roomscene-lofi'],
  },
  {
    id: 'slow-mornings', title: 'Slow Mornings', eyebrow: 'EASY DOES IT',
    description: 'Ease into the day, your way.',
    cover: asset('images/cover-slow-mornings.webp'), moods: ['Chill', 'Focus'],
    trackIds: ['morning-coffee', 'blue-skies', 'deja-vu', 'duru-roomscene-lofi', 'city-in-the-rearview'],
  },
  {
    id: 'electric-dreams', title: 'Electric Dreams', eyebrow: 'TURN IT UP',
    description: 'A little spark for your day.',
    cover: asset('images/cover-electric-dreams.webp'), moods: ['Electronic', 'Feel good'],
    trackIds: ['day-dreams', 'night-life', 'duru-rhythm-fever', 'happy-dance', 'breath'],
  },
  {
    id: 'focus-flow', title: 'Focus Flow', eyebrow: 'IN THE ZONE',
    description: 'Find your rhythm and stay there.',
    cover: asset('images/cover-focus-flow.webp'), moods: ['Focus', 'Chill'],
    trackIds: ['duru-roomscene-lofi', 'deja-vu', 'blue-skies', 'morning-coffee', 'city-in-the-rearview'],
  },
  {
    id: 'good-energy', title: 'Good Energy', eyebrow: 'FEEL GOOD',
    description: 'A brighter kind of soundtrack.',
    cover: asset('images/cover-good-energy.webp'), moods: ['Feel good'],
    trackIds: ['happy-dance', 'breath', 'blue-skies', 'duru-rhythm-fever', 'day-dreams'],
  },
  {
    id: 'after-hours', title: 'After Hours', eyebrow: 'SLOW IT DOWN',
    description: 'Sounds for when the day winds down.',
    cover: asset('images/cover-after-hours.webp'), moods: ['Chill', 'Electronic'],
    trackIds: ['deja-vu', 'city-in-the-rearview', 'night-life', 'morning-coffee', 'day-dreams'],
  },
]

export const playlistById: Record<string, CuratedPlaylist> = Object.fromEntries(
  curatedPlaylists.map((playlist) => [playlist.id, playlist]),
)

export const moods: Array<'All' | Mood> = ['All', 'Chill', 'Focus', 'Electronic', 'Feel good']

export function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const rounded = Math.floor(seconds)
  return `${Math.floor(rounded / 60)}:${String(rounded % 60).padStart(2, '0')}`
}

export function formatPlaylistLength(ids: string[]): string {
  const seconds = ids.reduce((total, id) => total + (trackById[id]?.duration ?? 0), 0)
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const remaining = minutes % 60
  return remaining ? `${hours} hr ${remaining} min` : `${hours} hr`
}

export function searchCatalog(query: string) {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return { tracks: [], playlists: [] }
  return {
    tracks: tracks.filter((track) => `${track.title} ${track.artist} ${track.album} ${track.moods.join(' ')}`.toLocaleLowerCase().includes(needle)),
    playlists: curatedPlaylists.filter((playlist) => `${playlist.title} ${playlist.description} ${playlist.moods.join(' ')}`.toLocaleLowerCase().includes(needle)),
  }
}
