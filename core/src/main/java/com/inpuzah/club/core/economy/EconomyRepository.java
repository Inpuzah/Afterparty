package com.inpuzah.club.core.economy;

import java.util.UUID;

/** Blocking persistence boundary; call only from workers, never the Bukkit thread. */
public interface EconomyRepository {

    BalanceSnapshot balance(UUID playerId);

    CurrencyOperationResult apply(CurrencyOperation operation);
}
