// GENERATED — DO NOT EDIT.
// Run `node design-system/scripts/generate-tokens.mjs` to regenerate.
//
// Source: design-system/design-tokens.json (plants.daily)

import Foundation

/// One ayurvedic plant in the daily-rotation list.
public struct DailyPlant: Equatable, Sendable {
    /// Sanskrit name shown as the page title (lowercase serif).
    public let name: String
    /// English / common name shown in a parenthetical after the
    /// Sanskrit. Empty string when the Sanskrit name is already the
    /// recognised English form.
    public let commonName: String
    /// One-line poetic descriptor. Sits at the head of the subtitle.
    public let epithet: String
    /// Comma-separated practical uses. Sits after the epithet,
    /// separated by a center dot.
    public let usedFor: String

    public init(name: String, commonName: String, epithet: String, usedFor: String) {
        self.name = name
        self.commonName = commonName
        self.epithet = epithet
        self.usedFor = usedFor
    }
}

/// Daily-plant rotation source for the page header. Each calendar
/// day picks one plant from the curated set below. Selection is
/// deterministic — same `dayOfYear`, same plant — and identical
/// to the Android side (`DailyPlants.generated.kt`).
public enum DailyPlants {

    /// Stable, append-only list. Reordering changes which plant
    /// shows on which day in the user's archive — append, never reshuffle.
    public static let all: [DailyPlant] = [
        DailyPlant(name: "tulasi", commonName: "holy basil", epithet: "the immune mother", usedFor: "immunity, respiratory, stress, sacred rituals"),
        DailyPlant(name: "nimba", commonName: "neem", epithet: "the village pharmacy", usedFor: "skin, dental care, blood purifier, antibacterial"),
        DailyPlant(name: "ashwagandha", commonName: "winter cherry", epithet: "strength of a horse", usedFor: "stress, vitality, sleep, strength"),
        DailyPlant(name: "amalaki", commonName: "Indian gooseberry", epithet: "the rejuvenator", usedFor: "vitamin C, hair, digestion, chyawanprash"),
        DailyPlant(name: "brahmi", commonName: "water hyssop", epithet: "the brain's gardener", usedFor: "memory, focus, anxiety relief"),
        DailyPlant(name: "shatavari", commonName: "wild asparagus", epithet: "she of a hundred roots", usedFor: "women's health, hormones, lactation"),
        DailyPlant(name: "arjuna", commonName: "arjun tree", epithet: "keeper of the heart", usedFor: "heart health, blood pressure"),
        DailyPlant(name: "haridra", commonName: "turmeric", epithet: "the golden anti-flame", usedFor: "anti-inflammatory, wounds, daily cooking"),
        DailyPlant(name: "bilva", commonName: "bael", epithet: "sacred three-leaf", usedFor: "digestion, sacred offerings, jaundice"),
        DailyPlant(name: "ashvattha", commonName: "sacred fig", epithet: "the breathing tree", usedFor: "air purification, longevity, sacred shade"),
        DailyPlant(name: "nyagrodha", commonName: "banyan", epithet: "roots that walk", usedFor: "longevity, shelter, spiritual rest"),
        DailyPlant(name: "guduchi", commonName: "giloy", epithet: "the nectar of immortality", usedFor: "fever, immunity, detox"),
        DailyPlant(name: "manjishtha", commonName: "Indian madder", epithet: "the blood's broom", usedFor: "skin, blood purifier, lymph"),
        DailyPlant(name: "bhringraj", commonName: "false daisy", epithet: "king of hair", usedFor: "hair growth, scalp health, liver"),
        DailyPlant(name: "lashuna", commonName: "garlic", epithet: "the kitchen's first cure", usedFor: "cold, cholesterol, immunity, daily cooking"),
        DailyPlant(name: "ardraka", commonName: "ginger", epithet: "fire root of the kitchen", usedFor: "digestion, nausea, cold, chai and curries"),
        DailyPlant(name: "maricha", commonName: "black pepper", epithet: "the king of spices", usedFor: "digestion, cold, seasoning, every kitchen"),
        DailyPlant(name: "jeeraka", commonName: "cumin", epithet: "the everyday ember", usedFor: "digestion, gas, tempering, masala"),
        DailyPlant(name: "dhanyaka", commonName: "coriander", epithet: "the cooling green", usedFor: "cooling, digestion, fresh garnish"),
        DailyPlant(name: "methika", commonName: "fenugreek", epithet: "the bitter blessing", usedFor: "diabetes, hair, digestion, sabzi"),
        DailyPlant(name: "ela", commonName: "cardamom", epithet: "the queen of spices", usedFor: "digestion, breath, chai, sweets"),
        DailyPlant(name: "lavanga", commonName: "cloves", epithet: "the pinpoint flame", usedFor: "toothache, digestion, biryani"),
        DailyPlant(name: "twak", commonName: "cinnamon", epithet: "the sweet bark", usedFor: "blood sugar, warming, baking, masala"),
        DailyPlant(name: "tamalapatra", commonName: "bay leaf", epithet: "leaf of the slow-cooked", usedFor: "flavoring, digestion, garam masala"),
        DailyPlant(name: "hingu", commonName: "asafoetida", epithet: "the loud resin", usedFor: "gas, digestion, dal tadka"),
        DailyPlant(name: "krishnanimba", commonName: "curry leaf", epithet: "the leaf of every pot", usedFor: "hair, eye health, daily tempering"),
        DailyPlant(name: "shigru", commonName: "moringa", epithet: "the miracle tree", usedFor: "nutrition, anemia, leaves and pods"),
        DailyPlant(name: "kumari", commonName: "aloe vera", epithet: "the youthful one", usedFor: "burns, skin, hair, gentle laxative"),
        DailyPlant(name: "haritaki", commonName: "chebulic myrobalan", epithet: "mother of all herbs", usedFor: "constipation, detox, triphala"),
        DailyPlant(name: "yashtimadhu", commonName: "licorice", epithet: "the sweet root", usedFor: "sore throat, ulcers, cough"),
        DailyPlant(name: "pippali", commonName: "long pepper", epithet: "the long warming flame", usedFor: "respiratory, digestion, cough")
    ]

    /// The plant for `date`, default today. Selection is
    /// `(dayOfYear - 1) % count` against the user's calendar — stable
    /// within a day, rotates across days.
    public static func forToday(
        date: Date = Date(),
        calendar: Calendar = .current
    ) -> DailyPlant {
        let dayOfYear = calendar.ordinality(of: .day, in: .year, for: date) ?? 1
        let index = (dayOfYear - 1) % all.count
        return all[index]
    }
}
