import { useEffect, useState } from 'react'

/** Keep the app usable even when storage is disabled or contains invalid data. */
export function useStoredState<T>(key: string, fallback: T, isValid: (value: unknown) => value is T) {
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = window.localStorage.getItem(key)
      if (stored !== null) {
        const parsed: unknown = JSON.parse(stored)
        if (isValid(parsed)) return parsed
      }
    } catch {
      // Private browsing or corrupt storage should not prevent listening.
    }
    return fallback
  })

  useEffect(() => {
    try {
      window.localStorage.setItem(key, JSON.stringify(value))
    } catch {
      // The current session still works without persistent storage.
    }
  }, [key, value])

  return [value, setValue] as const
}
