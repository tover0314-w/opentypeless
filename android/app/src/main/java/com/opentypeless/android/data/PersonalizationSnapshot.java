package com.opentypeless.android.data;

import java.util.List;

public record PersonalizationSnapshot(
        List<PersonalTerm> terms,
        List<CorrectionRule> corrections) {

    public static PersonalizationSnapshot empty() {
        return new PersonalizationSnapshot(List.of(), List.of());
    }
}
