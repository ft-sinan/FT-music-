package com.music.innertube.models

/**
 * YouTube Music exposes podcast shows behind the FEmusic_podcast_ browse
 * prefix (the raw show id follows it). PodcastPage normalises those ids so
 * the rest of the app only ever sees the plain show id.
 */
const val PODCAST_BROWSE_PREFIX = "FEmusic_podcast_"
