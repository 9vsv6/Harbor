package app.harbor.android.data

/**
 * Harbor's streaming catalogs (src/lib/providers/streaming.ts): the services whose top titles
 * get a row, with TMDB's watch-provider ids. Settings → Streaming services toggles them, with
 * Harbor's defaults (the big twelve on, the rest off).
 */
object StreamingServices {
    data class Service(val key: String, val name: String, val logo: String, val providerIds: List<Int>, val onByDefault: Boolean)

    val all = listOf(
        Service("netflix", "Netflix", "services/netflix.svg", listOf(8), true),
        Service("disney", "Disney+", "services/disney.svg", listOf(337), true),
        Service("hulu", "Hulu", "services/hulu.svg", listOf(15), true),
        Service("prime", "Prime Video", "services/prime.svg", listOf(9, 119), true),
        Service("apple", "Apple TV+", "services/apple.svg", listOf(350), true),
        Service("max", "Max", "services/max.svg", listOf(1899, 384), true),
        Service("paramount", "Paramount+", "services/paramount.svg", listOf(531, 582, 1715, 1854), true),
        Service("peacock", "Peacock", "services/peacock.svg", listOf(386, 387), true),
        Service("crunchyroll", "Crunchyroll", "services/crunchyroll.svg", listOf(283), true),
        Service("amcplus", "AMC+", "services/amcplus.svg", listOf(526), true),
        Service("starz", "STARZ", "services/starz.svg", listOf(43), true),
        Service("shudder", "Shudder", "services/shudder.svg", listOf(99), true),
        Service("tubi", "Tubi", "services/tubi.svg", listOf(73), false),
        Service("plutotv", "Pluto TV", "services/plutotv.svg", listOf(300), false),
        Service("roku", "The Roku Channel", "services/roku.svg", listOf(207), false),
        Service("fubo", "Fubo", "services/fubo.svg", listOf(257), false),
        Service("mgmplus", "MGM+", "services/mgmplus.svg", listOf(34, 583, 636), false),
        Service("philo", "Philo", "services/philo.svg", listOf(2383), false),
        Service("britbox", "BritBox", "services/britbox.svg", listOf(151), false),
        Service("acorntv", "Acorn TV", "services/acorntv.svg", listOf(87), false),
        Service("mubi", "MUBI", "services/mubi.svg", listOf(11), false),
        Service("curiositystream", "CuriosityStream", "services/curiositystream.svg", listOf(190), false),
        Service("kanopy", "Kanopy", "services/kanopy.png", listOf(191), false),
        Service("hoopla", "Hoopla", "services/hoopla.svg", listOf(212), false),
        Service("pbs", "PBS", "services/pbs.svg", listOf(209), false),
        Service("cw", "The CW", "services/cw.svg", listOf(83), false),
        Service("hidive", "HIDIVE", "services/hidive.png", listOf(430), false),
    )

    fun isOn(settings: AppSettings, service: Service) = settings.streaming[service.key] ?: service.onByDefault

    fun enabled(settings: AppSettings) = all.filter { isOn(settings, it) }
}
