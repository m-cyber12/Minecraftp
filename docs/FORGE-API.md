# CraftRealm Forge API

The Forge mod exposes `io.craftrealm.forge.api.ForgeEconomyAPI` for other Forge
mods. The instance is scoped to the active server and is cleared during shutdown.
`getInstance()` returns `null` while CraftRealm is stopped or before its server
services have initialized.

```java
ForgeEconomyAPI api = ForgeEconomyAPI.getInstance();
if (api != null) {
    api.balance(playerId, playerName).thenAccept(balanceCents -> {
        // This callback runs on a database worker thread; schedule game changes
        // with the server's execute method before touching Minecraft state.
    });

    api.pay(playerId, playerName, recipientId, recipientName, 250)
       .thenAccept(result -> {
           if (result.success()) {
               // The transfer committed; amounts and balances are exact cents.
           }
       });
}
```

`pay` completes with a `TransferResult` status for invalid amounts, self-payment,
insufficient funds, recipient limits, or event cancellation. Database failures
complete the future exceptionally. The API posts `ForgeBalanceChangeEvent` for
both sides of the transfer on the server thread before committing; other mods may
cancel either event. All storage operations remain asynchronous.

Market quote reads are available through `marketQuotes()`. The public event types
are `ForgeBalanceChangeEvent` and `ForgeTransactionType` in the same package.
