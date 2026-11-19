package net.dnd5ecraft.combat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ReactionWindowManager {
    public static final long WINDOW_MILLIS = 3_500;

    public enum Kind { OPPORTUNITY_ATTACK, SHIELD, COUNTERSPELL, UNCANNY_DODGE, CUTTING_WORDS, DEFLECT_MISSILES, READIED_ATTACK }
    public record Offer(UUID reactor, Kind kind) {
        public Offer { Objects.requireNonNull(reactor); Objects.requireNonNull(kind); }
    }
    public record Window(long id, long deadlineMillis, List<Offer> offers, Set<UUID> answered) {
        public Window { offers = List.copyOf(offers); answered = Set.copyOf(answered); }
    }

    private long sequence;
    private Window window;

    public Window open(List<Offer> offers, long nowMillis) {
        if (window != null) throw new IllegalStateException("A reaction prompt is already open");
        if (offers.isEmpty()) throw new IllegalArgumentException("At least one reaction must be offered");
        window = new Window(++sequence, Math.addExact(nowMillis, WINDOW_MILLIS),
                List.copyOf(new LinkedHashSet<>(offers)), Set.of());
        return window;
    }

    void cancel() { window = null; }

    public Optional<Window> current() { return Optional.ofNullable(window); }

    public boolean canAnswer(long id, UUID reactor, Kind kind, long nowMillis) {
        return window != null && window.id == id && nowMillis < window.deadlineMillis
                && !window.answered.contains(reactor)
                && window.offers.stream().anyMatch(offer -> offer.reactor.equals(reactor) && (kind == null || offer.kind == kind));
    }

    public boolean answer(long id, UUID reactor, Kind kind, long nowMillis) {
        if (!canAnswer(id, reactor, kind, nowMillis)) return false;
        Set<UUID> answered = new LinkedHashSet<>(window.answered);
        answered.add(reactor);
        window = new Window(window.id, window.deadlineMillis, window.offers, answered);
        return true;
    }

    public boolean closeIfResolved(long nowMillis) {
        if (window == null) return false;
        if (nowMillis < window.deadlineMillis
                && window.offers.stream().anyMatch(offer -> !window.answered.contains(offer.reactor))) {
            return false;
        }
        window = null;
        return true;
    }
}
