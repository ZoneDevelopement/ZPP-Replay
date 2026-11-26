package dev.nandi0813.zppreplay.play;

import java.util.concurrent.atomic.AtomicInteger;

/** Entity ids of the fake bodies. Server ids count up from 0, so these count down from 2 billion. */
public final class FakeIds {

    private static final AtomicInteger NEXT = new AtomicInteger(2_000_000_000);

    private FakeIds() {
    }

    public static int next() {
        return NEXT.getAndDecrement();
    }
}
