package vx.vivaldi

import vx.vivaldi.season.Season

object VivaldiAPI {

    // Безопасный доступ к менеджеру
    private val seasonManager get() = Vivaldi.plugin.seasonManager

    /**
     * Возвращает текущее время года.
     */
    @JvmStatic
    fun getCurrentSeason(): Season {
        return seasonManager.currentSeason
    }

    /**
     * Принудительно устанавливает новое время года.
     * Это вызовет SeasonChangeEvent и обновит мир.
     */
    @JvmStatic
    fun setSeason(season: Season) {
        seasonManager.setSeason(season)
    }

    /**
     * Переключает на следующий сезон (Весна -> Лето -> Осень -> Зима).
     */
    @JvmStatic
    fun nextSeason() {
        seasonManager.setSeason(seasonManager.currentSeason.next())
    }

}