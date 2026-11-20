package net.dnd5ecraft.rules.equipment;

import java.util.Optional;

public record CoinPurse(long copper, long silver, long electrum, long gold, long platinum) {
    public enum Denomination { CP, SP, EP, GP, PP }
    public record Transfer(CoinPurse payer, CoinPurse payee) {}

    public Optional<Transfer> transfer(CoinPurse recipient, long amount, Denomination denomination) {
        if (amount <= 0) throw new IllegalArgumentException("Coin transfer amount must be positive");
        CoinPurse payment = switch (denomination) {
            case CP -> new CoinPurse(amount, 0, 0, 0, 0);
            case SP -> new CoinPurse(0, amount, 0, 0, 0);
            case EP -> new CoinPurse(0, 0, amount, 0, 0);
            case GP -> new CoinPurse(0, 0, 0, amount, 0);
            case PP -> new CoinPurse(0, 0, 0, 0, amount);
        };
        if (copper < payment.copper || silver < payment.silver || electrum < payment.electrum
                || gold < payment.gold || platinum < payment.platinum) return Optional.empty();
        var remainder = new CoinPurse(copper - payment.copper, silver - payment.silver, electrum - payment.electrum,
                gold - payment.gold, platinum - payment.platinum);
        var credited = recipient.deposit(payment);
        credited.valueInCopper();
        return Optional.of(new Transfer(remainder, credited));
    }

    public static final CoinPurse EMPTY = new CoinPurse(0, 0, 0, 0, 0);

    public CoinPurse {
        if (copper < 0 || silver < 0 || electrum < 0 || gold < 0 || platinum < 0) {
            throw new IllegalArgumentException("Coin counts cannot be negative");
        }
    }

    public long valueInCopper() {
        long total = Math.addExact(copper, Math.multiplyExact(silver, 10));
        total = Math.addExact(total, Math.multiplyExact(electrum, 50));
        total = Math.addExact(total, Math.multiplyExact(gold, 100));
        return Math.addExact(total, Math.multiplyExact(platinum, 1000));
    }

    public double weightInPounds() {
        return (copper + (double) silver + electrum + gold + platinum) / 50.0;
    }

    public CoinPurse deposit(CoinPurse deposit) {
        return new CoinPurse(Math.addExact(copper, deposit.copper), Math.addExact(silver, deposit.silver),
                Math.addExact(electrum, deposit.electrum), Math.addExact(gold, deposit.gold),
                Math.addExact(platinum, deposit.platinum));
    }

    public Optional<CoinPurse> exchangeAndSpend(long priceInCopper) {
        if (priceInCopper < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
        if (priceInCopper == 0) {
            return Optional.of(this);
        }
        long remainder = valueInCopper() - priceInCopper;
        if (remainder < 0) {
            return Optional.empty();
        }
        return Optional.of(new CoinPurse(remainder % 10, remainder % 50 / 10, remainder % 100 / 50,
                remainder % 1000 / 100, remainder / 1000));
    }
}
