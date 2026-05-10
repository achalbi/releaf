/*
 * DesignationVocab.swift
 *
 * Vocabulary of designation tokens / phrases. Mirror of
 * `DesignationVocab.kt` — same membership, kept in sync manually.
 */

import Foundation

public enum DesignationVocab {

    public static let tokens: Set<String> = [
        // C-suite
        "ceo", "cto", "cfo", "coo", "cmo", "cio", "cpo", "cso", "chro", "ciso",
        // President / Founder
        "president", "founder", "cofounder", "co-founder", "owner", "proprietor",
        // Director / VP
        "director", "vp", "vice", "executive",
        "managing", "deputy", "associate", "assistant", "principal",
        // Manager
        "manager", "supervisor", "lead", "head", "chief",
        // Engineering
        "engineer", "developer", "programmer", "architect", "specialist", "consultant",
        "scientist", "analyst", "researcher", "technologist", "technician",
        // Sales / marketing
        "sales", "marketing", "business", "growth", "account", "partner", "partnerships",
        // Other
        "designer", "writer", "editor", "producer", "coordinator", "administrator",
        "advisor", "advocate", "officer", "secretary", "accountant", "auditor",
        "trainer", "teacher", "professor", "doctor", "dr",
        "attorney", "lawyer", "counsel", "physician", "surgeon",
    ]

    public static let phrases: [String] = [
        "vice president",
        "managing director",
        "general manager",
        "regional manager",
        "country head",
        "head of",
        "chief of",
        "director of",
        "manager of",
        "lead engineer",
        "senior engineer",
        "junior engineer",
        "software engineer",
        "data scientist",
        "product manager",
        "project manager",
        "program manager",
        "account manager",
    ]
}
