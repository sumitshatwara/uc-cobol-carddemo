package com.carddemo.common.export;

import java.util.SortedMap;
import java.util.TreeMap;

public record JobRc(int maxrc, SortedMap<String, Integer> steps) {
    public JobRc {
        steps = new TreeMap<>(steps);
    }
}
