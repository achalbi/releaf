/*
 * ScoringWeights.swift
 *
 * Centralised tunables for the business-card extraction pipeline.
 * Mirror of `ScoringWeights.kt` — same defaults, same semantics, so
 * benchmark grids run identically across both platforms.
 */

import Foundation

public struct ScoringWeights: Sendable {
    public let emailBase: Double
    public let phoneBase: Double
    public let websiteBase: Double
    public let designationBase: Double
    public let nameBase: Double
    public let companyBase: Double
    public let addressBase: Double
    public let postcodeBase: Double

    public let largeTextBonusMax: Double
    public let topPositionBonusMax: Double
    public let nameDesignationAdjacencyBonus: Double
    public let adjacencyYDistance: Double

    public let designationVocabBonus: Double
    public let companySuffixBonus: Double

    public let engineConfidenceWeight: Double

    public let nameDigitsPenalty: Double
    public let nameTokenCountPenalty: Double
    public let companyPunctuationPenalty: Double
    public let duplicateOverlapPenalty: Double
    /// Penalty applied to NAME candidates whose block height is at
    /// or near `layout.maxHeight`. Mirror of the Android weight.
    public let largestTextPenaltyForName: Double
    /// Bonus when the block's first token is a known honorific
    /// ("Mr", "Mrs", "Dr", "Sri", "Smt", …). Mirror of the Android
    /// weight.
    public let nameSalutationBonus: Double

    public let minNameScore: Double
    public let minCompanyScore: Double
    public let minDesignationScore: Double
    public let minAddressScore: Double
    public let minPhoneScore: Double
    public let minEmailScore: Double
    public let minWebsiteScore: Double

    public init(
        emailBase: Double                      = 10.0,
        phoneBase: Double                      = 9.0,
        websiteBase: Double                    = 8.0,
        designationBase: Double                = 5.0,
        nameBase: Double                       = 4.0,
        companyBase: Double                    = 4.0,
        addressBase: Double                    = 4.0,
        postcodeBase: Double                   = 6.0,
        largeTextBonusMax: Double              = 6.0,
        topPositionBonusMax: Double            = 6.0,
        nameDesignationAdjacencyBonus: Double  = 4.0,
        adjacencyYDistance: Double             = 0.10,
        designationVocabBonus: Double          = 5.0,
        companySuffixBonus: Double             = 5.0,
        engineConfidenceWeight: Double         = 4.0,
        nameDigitsPenalty: Double              = 10.0,
        nameTokenCountPenalty: Double          = 5.0,
        companyPunctuationPenalty: Double      = 3.0,
        duplicateOverlapPenalty: Double        = 2.0,
        largestTextPenaltyForName: Double      = 4.0,
        nameSalutationBonus: Double            = 6.0,
        minNameScore: Double                   = 5.0,
        minCompanyScore: Double                = 5.0,
        minDesignationScore: Double            = 5.0,
        minAddressScore: Double                = 4.0,
        minPhoneScore: Double                  = 6.0,
        minEmailScore: Double                  = 8.0,
        minWebsiteScore: Double                = 6.0
    ) {
        self.emailBase                         = emailBase
        self.phoneBase                         = phoneBase
        self.websiteBase                       = websiteBase
        self.designationBase                   = designationBase
        self.nameBase                          = nameBase
        self.companyBase                       = companyBase
        self.addressBase                       = addressBase
        self.postcodeBase                      = postcodeBase
        self.largeTextBonusMax                 = largeTextBonusMax
        self.topPositionBonusMax               = topPositionBonusMax
        self.nameDesignationAdjacencyBonus     = nameDesignationAdjacencyBonus
        self.adjacencyYDistance                = adjacencyYDistance
        self.designationVocabBonus             = designationVocabBonus
        self.companySuffixBonus                = companySuffixBonus
        self.engineConfidenceWeight            = engineConfidenceWeight
        self.nameDigitsPenalty                 = nameDigitsPenalty
        self.nameTokenCountPenalty             = nameTokenCountPenalty
        self.companyPunctuationPenalty         = companyPunctuationPenalty
        self.duplicateOverlapPenalty           = duplicateOverlapPenalty
        self.largestTextPenaltyForName         = largestTextPenaltyForName
        self.nameSalutationBonus               = nameSalutationBonus
        self.minNameScore                      = minNameScore
        self.minCompanyScore                   = minCompanyScore
        self.minDesignationScore               = minDesignationScore
        self.minAddressScore                   = minAddressScore
        self.minPhoneScore                     = minPhoneScore
        self.minEmailScore                     = minEmailScore
        self.minWebsiteScore                   = minWebsiteScore
    }
}
