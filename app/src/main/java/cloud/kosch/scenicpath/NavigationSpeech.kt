package cloud.kosch.scenicpath

import java.util.Locale

/** Pure navigation phrasing. TTS can therefore follow the device language without paid cloud voices. */
object NavigationSpeech {
    fun instruction(turn: NavigationTurn, locale: Locale): String = when (locale.language.lowercase(Locale.ROOT)) {
        "de" -> when (turn) {
            NavigationTurn.STRAIGHT -> "Geradeaus weiterfahren"
            NavigationTurn.SLIGHT_LEFT -> "Leicht links halten"
            NavigationTurn.LEFT -> "Links abbiegen"
            NavigationTurn.SHARP_LEFT -> "Scharf links abbiegen"
            NavigationTurn.U_TURN -> "Bitte wenden"
            NavigationTurn.SHARP_RIGHT -> "Scharf rechts abbiegen"
            NavigationTurn.RIGHT -> "Rechts abbiegen"
            NavigationTurn.SLIGHT_RIGHT -> "Leicht rechts halten"
            NavigationTurn.ARRIVE -> "Ziel erreicht"
        }
        else -> when (turn) {
            NavigationTurn.STRAIGHT -> "Continue straight"
            NavigationTurn.SLIGHT_LEFT -> "Bear slightly left"
            NavigationTurn.LEFT -> "Turn left"
            NavigationTurn.SHARP_LEFT -> "Turn sharp left"
            NavigationTurn.U_TURN -> "Make a U-turn"
            NavigationTurn.SHARP_RIGHT -> "Turn sharp right"
            NavigationTurn.RIGHT -> "Turn right"
            NavigationTurn.SLIGHT_RIGHT -> "Bear slightly right"
            NavigationTurn.ARRIVE -> "Destination reached"
        }
    }

    fun turnAnnouncement(turn: NavigationTurn, bucketMeters: Int, locale: Locale): String {
        val instruction = instruction(turn, locale)
        return when (locale.language.lowercase(Locale.ROOT)) {
            "de" -> when (bucketMeters) {
                850 -> "In etwa achthundert Metern, ${instruction.lowercase(locale)}."
                260 -> "In etwa zweihundertfünfzig Metern, ${instruction.lowercase(locale)}."
                else -> "$instruction."
            }
            else -> when (bucketMeters) {
                850 -> "In about eight hundred meters, ${instruction.lowercase(locale)}."
                260 -> "In about two hundred fifty meters, ${instruction.lowercase(locale)}."
                else -> "$instruction."
            }
        }
    }

    fun arrival(locale: Locale): String = if (locale.language.equals("de", true)) {
        "Du hast dein Ziel erreicht."
    } else {
        "You have reached your destination."
    }

    fun offRoute(locale: Locale): String = if (locale.language.equals("de", true)) {
        "Du hast die geplante Route verlassen. Eine neue Route kann berechnet werden."
    } else {
        "You are off the planned route. A new route can be calculated."
    }

    fun stopAhead(name: String, locale: Locale): String = if (locale.language.equals("de", true)) {
        "Dein Scenic Zwischenstopp $name liegt direkt voraus."
    } else {
        "Your Scenic stop, $name, is just ahead."
    }

    fun stopInOneKm(name: String, locale: Locale): String = if (locale.language.equals("de", true)) {
        "In etwa einem Kilometer, Scenic Zwischenstopp $name."
    } else {
        "In about one kilometer, Scenic stop $name."
    }
}
