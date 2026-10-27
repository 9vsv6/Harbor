package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

/** Cloudstream's activity, for the events a few extensions listen to; nothing fires them here. */
class MainActivity {
    companion object {
        val afterPluginsLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
    }
}
