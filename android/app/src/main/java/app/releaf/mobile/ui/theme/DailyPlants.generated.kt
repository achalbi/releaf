// GENERATED — DO NOT EDIT.
// Run `node design-system/scripts/generate-tokens.mjs` to regenerate.
//
// Source: design-system/design-tokens.json (plants.daily)

package app.releaf.mobile.ui.theme

import java.time.LocalDate

/** One ayurvedic plant in the daily-rotation list. */
data class DailyPlant(
    /** Sanskrit name shown as the page title (lowercase serif). */
    val name: String,
    /** English / common name shown in a parenthetical after the
     *  Sanskrit. Empty string when the Sanskrit name is already the
     *  recognised English form. */
    val commonName: String,
    /** One-line poetic descriptor. Sits at the head of the subtitle. */
    val epithet: String,
    /** Comma-separated practical uses. Sits after the epithet,
     *  separated by a center dot. */
    val usedFor: String,
)

/**
 * Daily-plant rotation source for the page header. Each calendar
 * day picks one plant from the curated set below. Selection is
 * deterministic — same `dayOfYear`, same plant — and identical
 * to the iOS side (`DailyPlants.generated.swift`).
 */
object DailyPlants {

    /** Stable, append-only list. Reordering changes which plant
     *  shows on which day in the user's archive — append, never reshuffle. */
    val all: List<DailyPlant> = listOf(
        DailyPlant("tulasi", "holy basil", "the immune mother", "immunity, respiratory, stress, sacred rituals"),
        DailyPlant("nimba", "neem", "the village pharmacy", "skin, dental care, blood purifier, antibacterial"),
        DailyPlant("ashwagandha", "winter cherry", "strength of a horse", "stress, vitality, sleep, strength"),
        DailyPlant("amalaki", "Indian gooseberry", "the rejuvenator", "vitamin C, hair, digestion, chyawanprash"),
        DailyPlant("brahmi", "water hyssop", "the brain's gardener", "memory, focus, anxiety relief"),
        DailyPlant("shatavari", "wild asparagus", "she of a hundred roots", "women's health, hormones, lactation"),
        DailyPlant("arjuna", "arjun tree", "keeper of the heart", "heart health, blood pressure"),
        DailyPlant("haridra", "turmeric", "the golden anti-flame", "anti-inflammatory, wounds, daily cooking"),
        DailyPlant("bilva", "bael", "sacred three-leaf", "digestion, sacred offerings, jaundice"),
        DailyPlant("ashvattha", "sacred fig", "the breathing tree", "air purification, longevity, sacred shade"),
        DailyPlant("nyagrodha", "banyan", "roots that walk", "longevity, shelter, spiritual rest"),
        DailyPlant("guduchi", "giloy", "the nectar of immortality", "fever, immunity, detox"),
        DailyPlant("manjishtha", "Indian madder", "the blood's broom", "skin, blood purifier, lymph"),
        DailyPlant("bhringraj", "false daisy", "king of hair", "hair growth, scalp health, liver"),
        DailyPlant("lashuna", "garlic", "the kitchen's first cure", "cold, cholesterol, immunity, daily cooking"),
        DailyPlant("ardraka", "ginger", "fire root of the kitchen", "digestion, nausea, cold, chai and curries"),
        DailyPlant("maricha", "black pepper", "the king of spices", "digestion, cold, seasoning, every kitchen"),
        DailyPlant("jeeraka", "cumin", "the everyday ember", "digestion, gas, tempering, masala"),
        DailyPlant("dhanyaka", "coriander", "the cooling green", "cooling, digestion, fresh garnish"),
        DailyPlant("methika", "fenugreek", "the bitter blessing", "diabetes, hair, digestion, sabzi"),
        DailyPlant("ela", "cardamom", "the queen of spices", "digestion, breath, chai, sweets"),
        DailyPlant("lavanga", "cloves", "the pinpoint flame", "toothache, digestion, biryani"),
        DailyPlant("twak", "cinnamon", "the sweet bark", "blood sugar, warming, baking, masala"),
        DailyPlant("tamalapatra", "bay leaf", "leaf of the slow-cooked", "flavoring, digestion, garam masala"),
        DailyPlant("hingu", "asafoetida", "the loud resin", "gas, digestion, dal tadka"),
        DailyPlant("krishnanimba", "curry leaf", "the leaf of every pot", "hair, eye health, daily tempering"),
        DailyPlant("shigru", "moringa", "the miracle tree", "nutrition, anemia, leaves and pods"),
        DailyPlant("kumari", "aloe vera", "the youthful one", "burns, skin, hair, gentle laxative"),
        DailyPlant("haritaki", "chebulic myrobalan", "mother of all herbs", "constipation, detox, triphala"),
        DailyPlant("yashtimadhu", "licorice", "the sweet root", "sore throat, ulcers, cough"),
        DailyPlant("pippali", "long pepper", "the long warming flame", "respiratory, digestion, cough")
    )

    /** The plant for [date], default today. Selection is
     *  `(dayOfYear - 1) % count` against the user's local calendar —
     *  stable within a day, rotates across days. */
    fun forToday(date: LocalDate = LocalDate.now()): DailyPlant {
        val index = (date.dayOfYear - 1).mod(all.size)
        return all[index]
    }
}
