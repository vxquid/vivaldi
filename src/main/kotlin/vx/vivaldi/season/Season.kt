package vx.vivaldi.season

enum class Season {

    SPRING, SUMMER, AUTUMN, WINTER;

    fun next(): Season {
        val values = entries.toTypedArray()
        return values[(this.ordinal + 1) % values.size]
    }

}