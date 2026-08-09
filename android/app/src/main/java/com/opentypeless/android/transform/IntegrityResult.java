package com.opentypeless.android.transform;

import java.util.List;

public record IntegrityResult(boolean safe, List<String> reasons) {
    public static IntegrityResult ok() {
        return new IntegrityResult(true, List.of());
    }
}
