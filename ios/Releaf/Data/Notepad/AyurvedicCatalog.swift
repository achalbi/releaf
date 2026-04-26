/*
 * AyurvedicCatalog.swift
 *
 * Mirror of `AyurvedicCatalog.kt` — same plant list, same formatter
 * contract, so a notepad entry seeded on Android renders the same way
 * on iOS once it round-trips through Drive sync.
 *
 * Repository-level rule (see `NotepadRepository.create`): both `title`
 * and `description` are auto-filled together as a pair from the same
 * plant — and ONLY when both were left blank by the caller. If the
 * caller supplied either, we keep their values verbatim and skip the
 * seed entirely; otherwise mixing an authored title with an unrelated
 * auto-description would produce mismatched rows.
 *
 * Selection: deterministic on the entry id (UUIDv7). We use a custom
 * djb2 hash because Swift's built-in `String.hashValue` is salted
 * per-process (SE-0206) — that's fine for in-memory hash tables but
 * would pick a different plant for the same id on different launches
 * if anyone re-derives the seed later.
 */

import Foundation

public enum AyurvedicCatalog {

    public struct Plant: Equatable, Sendable {
        /// Sanskrit / Hindi vernacular name — e.g. "Tulsi".
        public let name: String
        /// English common name — shown in parens.
        public let commonName: String
        /// Poetic epithet — a short evocative byname for the plant
        /// (e.g. "the immune mother" for Tulsi). NOT the botanical
        /// specific epithet / species word; this is the literary kind
        /// of epithet, in the spirit of Homer's "rosy-fingered dawn".
        /// Reads as the plant's character in one phrase.
        public let epithet: String
        /// Short comma-joined list of traditional Ayurvedic uses.
        public let usedFor: String

        public init(name: String, commonName: String, epithet: String, usedFor: String) {
            self.name = name
            self.commonName = commonName
            self.epithet = epithet
            self.usedFor = usedFor
        }
    }

    public static let plants: [Plant] = [
        Plant(name: "Tulsi",        commonName: "Holy Basil",                epithet: "the immune mother",                usedFor: "cough, cold, stress, immunity"),
        Plant(name: "Ashwagandha",  commonName: "Indian Winter Cherry",      epithet: "the strength of a stallion",       usedFor: "stress, sleep, vitality"),
        Plant(name: "Neem",         commonName: "Margosa",                   epithet: "the village pharmacy",             usedFor: "skin, immunity, blood purification"),
        Plant(name: "Brahmi",       commonName: "Water Hyssop",              epithet: "the mind's herb",                  usedFor: "memory, focus, calm"),
        Plant(name: "Amla",         commonName: "Indian Gooseberry",         epithet: "the great rejuvenator",            usedFor: "digestion, hair, immunity"),
        Plant(name: "Haldi",        commonName: "Turmeric",                  epithet: "the golden healer",                usedFor: "inflammation, joints, healing"),
        Plant(name: "Shatavari",    commonName: "Wild Asparagus",            epithet: "the woman's friend",               usedFor: "hormonal balance, vitality"),
        Plant(name: "Guduchi",      commonName: "Heart-leaved Moonseed",     epithet: "the divine nectar",                usedFor: "fever, immunity, liver"),
        Plant(name: "Arjuna",       commonName: "Arjun Tree",                epithet: "guardian of the heart",            usedFor: "heart, circulation, blood pressure"),
        Plant(name: "Bibhitaki",    commonName: "Beleric",                   epithet: "the fearless one",                 usedFor: "respiratory, eyes, hair"),
        Plant(name: "Haritaki",     commonName: "Chebulic Myrobalan",        epithet: "king of medicines",                usedFor: "digestion, colon, longevity"),
        Plant(name: "Pippali",      commonName: "Long Pepper",               epithet: "kindler of digestive fire",        usedFor: "respiratory, digestion, metabolism"),
        Plant(name: "Manjistha",    commonName: "Indian Madder",             epithet: "the blood cleanser",               usedFor: "skin, blood, lymphatic"),
        Plant(name: "Vasaka",       commonName: "Malabar Nut",               epithet: "the breath restorer",              usedFor: "cough, asthma, bronchitis"),
        Plant(name: "Kutki",        commonName: "Picrorhiza",                epithet: "the bitter friend of the liver",   usedFor: "liver, fever, digestion"),
        Plant(name: "Bhringaraj",   commonName: "False Daisy",               epithet: "ruler of the hair",                usedFor: "hair, liver, skin"),
        Plant(name: "Yashtimadhu",  commonName: "Licorice",                  epithet: "the sweet root",                   usedFor: "throat, ulcers, immunity"),
        Plant(name: "Punarnava",    commonName: "Hogweed",                   epithet: "that which renews",                usedFor: "kidneys, liver, edema"),
        Plant(name: "Gokshura",     commonName: "Small Caltrops",            epithet: "the cow's hoof",                   usedFor: "urinary, vitality, kidneys"),
        Plant(name: "Vidanga",      commonName: "False Black Pepper",        epithet: "the worm's enemy",                 usedFor: "digestion, parasites, weight"),
        Plant(name: "Methi",        commonName: "Fenugreek",                 epithet: "the milk-giver's herb",            usedFor: "blood sugar, digestion, lactation"),
        Plant(name: "Jatamansi",    commonName: "Spikenard",                 epithet: "calmer of nerves",                 usedFor: "sleep, anxiety, mood"),
        Plant(name: "Kapikacchu",   commonName: "Velvet Bean",               epithet: "the monkey's itch herb",           usedFor: "nervous system, vitality, mood"),
        Plant(name: "Bilva",        commonName: "Bael",                      epithet: "Shiva's tree",                     usedFor: "digestion, diarrhea, intestinal health"),
        Plant(name: "Shankhpushpi", commonName: "Aloe Weed",                 epithet: "the conch flower of memory",       usedFor: "memory, calm, sleep"),
    ]

    /// Pick a plant deterministically from a stable id (UUIDv7). Same
    /// id → same plant; consecutive UUIDv7s differ in the random tail
    /// so adjacent fresh entries land on different rows without a
    /// global counter.
    public static func plant(forId id: String) -> Plant {
        let bucket = abs(id.djb2Hash) % plants.count
        return plants[bucket]
    }

    /// Render a plant as the seed description (paired with `name` in
    /// the row's `title` column). Format:
    /// `(<commonName>), <epithet>, Used for <usedFor>`.
    ///
    /// Example: `(Holy Basil), the immune mother, Used for cough,
    /// cold, stress, immunity`. The vernacular `name` is omitted on
    /// purpose — the title column already carries it. `epithet` here
    /// is a poetic byname (see the field doc on `Plant.epithet`),
    /// not a botanical species word.
    public static func description(for plant: Plant) -> String {
        "(\(plant.commonName)), \(plant.epithet), Used for \(plant.usedFor)"
    }
}

private extension String {
    /// djb2 string hash. Used because Swift's built-in `hashValue`
    /// is salted per-process (SE-0206) and we want a stable bucket
    /// index across launches if anyone re-derives the seed.
    var djb2Hash: Int {
        var hash = 5381
        for byte in utf8 {
            hash = ((hash &<< 5) &+ hash) &+ Int(byte)
        }
        return hash
    }
}
