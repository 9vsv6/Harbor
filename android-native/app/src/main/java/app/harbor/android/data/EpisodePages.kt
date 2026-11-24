package app.harbor.android.data

object EpisodePages {
    const val SIZE = 50
    fun count(total: Int): Int = maxOf(1, (total.coerceAtLeast(0) + SIZE - 1) / SIZE)
    fun safe(page: Int, total: Int): Int = page.coerceIn(0, count(total) - 1)
    fun parse(value: String, total: Int): Int? = value.trim().takeIf { it.matches(Regex("[0-9]+")) }
        ?.toIntOrNull()?.takeIf { it in 1..count(total) }?.minus(1)
    fun window(page: Int, total: Int): List<Int> {
        val current = safe(page, total) + 1
        return (listOf(1, count(total)) + (current - 2..current + 2)).distinct().filter { it in 1..count(total) }.sorted()
    }
    fun <T> slice(items: List<T>, page: Int): List<T> = items.drop(safe(page, items.size) * SIZE).take(SIZE)
}
