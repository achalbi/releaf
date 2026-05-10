/*
 * CompanySuffixVocab.swift
 *
 * Mirror of `CompanySuffixVocab.kt`.
 */

import Foundation

public enum CompanySuffixVocab {

    public static let singleTokens: Set<String> = [
        "inc", "incorporated",
        "ltd", "limited",
        "llc", "lp", "llp",
        "corp", "corporation",
        "co", "company",
        "plc",
        "gmbh", "ag", "sa", "sas", "bv", "nv",
        "pte", "pty",
        "kk", "kabushiki",
        "pvt", "private",
        "bharati", "ventures",
        "industries", "enterprises", "trading",
        "labs", "studio", "studios", "works", "group",
        "technologies", "tech", "systems", "solutions",
        "consulting", "consultants",
        "services", "associates", "partners",
        "holdings", "global", "international",
    ]

    public static let multiTokens: [String] = [
        "pvt ltd",
        "private limited",
        "pvt limited",
        "private ltd",
        "co ltd",
        "co limited",
        "company limited",
        "and co",
        "and company",
        "and sons",
        "and associates",
        "and partners",
    ]
}
