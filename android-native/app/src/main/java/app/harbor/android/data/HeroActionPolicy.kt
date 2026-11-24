package app.harbor.android.data

internal fun libraryScope(saved: String?): String = if (saved == "all") "all" else "library"

internal fun rememberedLibraryScope(saved: String?, adding: Boolean, requested: String): String =
    libraryScope(if (adding) requested else saved)

/** Measure both expanded rows, so translated labels and font scaling choose the same stages. */
internal fun heroActionStage(available: Int, expanded: Int, foldedCircles: Int): Int = when {
    expanded <= available -> 0
    foldedCircles <= available -> 1
    else -> 2
}
