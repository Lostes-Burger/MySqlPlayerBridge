package de.lostesburger.mySqlPlayerBridge.Managers.Vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultManagerTest {
    private final Player player = player(UUID.randomUUID());

    @Test
    void depositsOnlyTheMissingDifference() {
        FakeEconomy provider = new FakeEconomy(10.0d, 2);
        VaultManager manager = new VaultManager(provider.proxy());

        manager.setBalance(this.player, 15.25d);

        assertEquals(15.25d, provider.balance);
        assertEquals(List.of(5.25d), provider.deposits);
        assertTrue(provider.withdrawals.isEmpty());
    }

    @Test
    void withdrawsOnlyTheExcessDifference() {
        FakeEconomy provider = new FakeEconomy(15.25d, 2);
        VaultManager manager = new VaultManager(provider.proxy());

        manager.setBalance(this.player, 10.0d);

        assertEquals(10.0d, provider.balance);
        assertEquals(List.of(5.25d), provider.withdrawals);
        assertTrue(provider.deposits.isEmpty());
    }

    @Test
    void roundedMatchingBalanceDoesNotCreateAProviderTransaction() {
        FakeEconomy provider = new FakeEconomy(10.0d, 2);
        VaultManager manager = new VaultManager(provider.proxy());

        manager.setBalance(this.player, 10.004d);

        assertTrue(provider.deposits.isEmpty());
        assertTrue(provider.withdrawals.isEmpty());
    }

    @Test
    void failedProviderTransactionIsPropagated() {
        FakeEconomy provider = new FakeEconomy(10.0d, 2);
        provider.failTransactions = true;
        VaultManager manager = new VaultManager(provider.proxy());

        EconomyTransactionException exception = assertThrows(
                EconomyTransactionException.class,
                () -> manager.setBalance(this.player, 15.0d)
        );

        assertTrue(exception.getMessage().contains("TestEconomy"));
        assertEquals(10.0d, provider.balance);
    }

    @Test
    void nonFiniteBalancesAreRejected() {
        FakeEconomy provider = new FakeEconomy(10.0d, 2);
        VaultManager manager = new VaultManager(provider.proxy());

        assertThrows(IllegalArgumentException.class,
                () -> manager.setBalance(this.player, Double.NaN));

        provider.balance = Double.POSITIVE_INFINITY;
        assertThrows(EconomyTransactionException.class,
                () -> manager.getBalance(this.player));
    }

    private static Player player(UUID playerUuid) {
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> playerUuid;
            case "getName" -> "TestPlayer";
            case "isOnline" -> true;
            case "hashCode" -> playerUuid.hashCode();
            case "equals" -> proxy == arguments[0];
            case "toString" -> "TestPlayer[" + playerUuid + ']';
            default -> defaultValue(method.getReturnType());
        };
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class FakeEconomy implements InvocationHandler {
        private final int fractionalDigits;
        private final List<Double> deposits = new ArrayList<>();
        private final List<Double> withdrawals = new ArrayList<>();
        private double balance;
        private boolean failTransactions;

        private FakeEconomy(double balance, int fractionalDigits) {
            this.balance = balance;
            this.fractionalDigits = fractionalDigits;
        }

        private Economy proxy() {
            return (Economy) Proxy.newProxyInstance(
                    Economy.class.getClassLoader(),
                    new Class<?>[]{Economy.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "TestEconomy";
                case "fractionalDigits" -> this.fractionalDigits;
                case "getBalance" -> this.balance;
                case "depositPlayer" -> transact(((Number) arguments[1]).doubleValue(), true);
                case "withdrawPlayer" -> transact(((Number) arguments[1]).doubleValue(), false);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "TestEconomy";
                default -> defaultValue(method.getReturnType());
            };
        }

        private EconomyResponse transact(double amount, boolean deposit) {
            if (this.failTransactions) {
                return new EconomyResponse(
                        amount,
                        this.balance,
                        EconomyResponse.ResponseType.FAILURE,
                        "simulated failure"
                );
            }
            if (deposit) {
                this.deposits.add(amount);
                this.balance += amount;
            } else {
                this.withdrawals.add(amount);
                this.balance -= amount;
            }
            return new EconomyResponse(
                    amount,
                    this.balance,
                    EconomyResponse.ResponseType.SUCCESS,
                    null
            );
        }
    }
}
