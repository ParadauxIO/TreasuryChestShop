# ChestShop Treasury Integration Spec

## Goal

Modify ChestShop to use the **TreasuryApi** directly instead of the Vault Economy interface. This enables ChestShop to support both **PERSONAL** and **BUSINESS** accounts natively — a player can configure a chest shop to withdraw from / deposit to a specific business account rather than always using their personal balance.

## Obtaining the TreasuryApi

Treasury registers `TreasuryApi` as a Bukkit service. Obtain it on enable:

```java
RegisteredServiceProvider<TreasuryApi> rsp =
    Bukkit.getServicesManager().getRegistration(TreasuryApi.class);
if (rsp == null) {
    getLogger().severe("Treasury plugin not found!");
    return;
}
TreasuryApi treasury = rsp.getProvider();
```

Add `Treasury` to `depend` (or `softdepend`) in `plugin.yml`.

## Treasury Data Model Reference

### Account

```java
public class Account {
    Integer accountId;
    String accountType;   // PERSONAL | BUSINESS | GOVERNMENT | SYSTEM
    UUID ownerUuid;
    String displayName;
    boolean requiresAuthorization;
    boolean isArchived;
    boolean allowOverdraft;
    BigDecimal creditLimit;
}
```

### TransferRequest

```java
public record TransferRequest(
    int fromAccountId,
    int toAccountId,
    BigDecimal amount,
    String message,
    UUID initiator,            // the player or system UUID that triggered this
    @Nullable UUID authorizer, // required if either account has requiresAuthorization=true
    @Nullable String pluginSystem, // attribution string, e.g. "ChestShop"
    byte @Nullable [] dedupKey     // SHA-256 bytes for idempotency, or null
) {}
```

### Page (paginated results)

```java
public record Page<T>(List<T> items, int totalCount, int offset, int limit) {
    boolean hasMore();
    int pageNumber();
    int totalPages();
}
```

### TransactionEntry (history rows)

```java
public class TransactionEntry {
    long postingId;
    long txnId;
    int accountId;
    BigDecimal amount;    // positive = credit, negative = debit
    String memo;
    Instant settlementTime;
    String message;
    UUID initiatorUuid;
    UUID authorizerUuid;
    String pluginSystem;
}
```

### Idempotency utility

```java
// net.democracycraft.treasury.utils.Idempotency
public static byte[] sha256(String key);
```

Use this to generate dedup keys for TransferRequest. If the same dedupKey is submitted twice, the transfer returns the original txnId without double-posting.

## TreasuryApi Methods (full interface)

```java
// ---- Balance ----
BigDecimal getBalanceByAccountId(int accountId);
BigDecimal getBalanceByOwnerUuid(UUID ownerUuid);
boolean hasFunds(int accountId, BigDecimal amount);

// ---- Account lookups ----
Account getAccountByUUID(UUID ownerUuid);          // personal account by player UUID
Account getAccountById(int accountId);
List<Account> getAccountsByOwner(UUID ownerUuid);
List<Account> getAccountsByTypeAndOwner(String accountType, UUID ownerUuid);
List<Account> getAccountsByMember(UUID memberUuid);
boolean hasAccountByAccountId(int accountId);
boolean hasAccountByOwnerUuid(UUID ownerUuid);

// ---- Access control checks ----
boolean isAccountMember(UUID uuid, int accountId);
boolean isOwnerForAccountId(UUID uuid, int accountId);
boolean canAccessAccount(UUID uuid, int accountId);
boolean accountHasBalance(UUID uuid, int accountId);

// ---- Account lifecycle ----
Account resolveOrCreatePersonal(UUID playerUuid);
Account createAccount(String accountType, UUID ownerUuid, String displayName);
void updateAccount(Account account);
void archiveAccount(int accountId);
void unarchiveAccount(int accountId);

// ---- Member / authorizer management ----
void addMember(int accountId, UUID memberUuid, UUID addedByUuid);
void removeMember(int accountId, UUID memberUuid);
List<AccountMember> getMembers(int accountId);
void addAuthorizer(int accountId, UUID authorizerUuid, UUID addedByUuid);
void removeAuthorizer(int accountId, UUID authorizerUuid);
List<AccountMember> getAuthorizers(int accountId);

// ---- Transaction history (paginated) ----
Page<TransactionEntry> getTransactionHistory(int accountId, int offset, int limit);
LedgerTxn getTransaction(long txnId);
List<LedgerPosting> getPostingsForTransaction(long txnId);

// ---- Transfers ----
long transfer(TransferRequest transferRequest);

// ---- Formatting ----
String formatAmount(BigDecimal amount);
String getCurrencyNameSingular();
String getCurrencyNamePlural();
```

## What Needs to Change in ChestShop

### 1. Replace Vault Economy dependency with TreasuryApi

Everywhere ChestShop currently calls `Economy.getBalance()`, `Economy.withdrawPlayer()`, `Economy.depositPlayer()`, etc., replace with TreasuryApi calls.

The key difference: Vault operates on player names/UUIDs with doubles. TreasuryApi operates on **account IDs** with BigDecimal, supporting both personal and business accounts.

### 2. Account resolution for shop transactions

When a player interacts with a chest shop, ChestShop needs to determine which account to use for the **shop owner** side of the transaction:

- **Personal shop**: Use `treasury.resolveOrCreatePersonal(ownerUuid)` to get the owner's personal account.
- **Business shop**: The shop sign or config should store a `businessAccountId`. Use `treasury.getAccountById(businessAccountId)` to resolve it. Verify the shop operator has access via `treasury.canAccessAccount(operatorUuid, businessAccountId)`.

The **buyer/seller** (the player interacting with the shop) always uses their personal account: `treasury.resolveOrCreatePersonal(playerUuid)`.

### 3. Shop sign format for business accounts

Extend the chest shop sign format to optionally specify a business account. For example:

```
Line 1: [quantity]
Line 2: B [price] : S [price]
Line 3: [item]
Line 4: [ownerName] or [B:accountId]
```

Where `B:42` on line 4 would mean "use business account #42" instead of the player's personal account. When creating the sign, verify the player has access to that business account via `canAccessAccount`.

### 4. Transaction execution flow

**Buy flow** (player buys item from shop):

```java
UUID buyerUuid = buyer.getUniqueId();
Account buyerAccount = treasury.resolveOrCreatePersonal(buyerUuid);
// shopAccount is either personal or business depending on sign
Account shopAccount = resolveShopAccount(sign);
BigDecimal price = ...;

// Pre-check
if (!treasury.hasFunds(buyerAccount.getAccountId(), price)) {
    // "Not enough money"
    return;
}

// Execute transfer with idempotency
byte[] dedup = Idempotency.sha256("chestshop:" + signLocation + ":" + System.currentTimeMillis());
TransferRequest req = new TransferRequest(
    buyerAccount.getAccountId(),
    shopAccount.getAccountId(),
    price,
    "ChestShop purchase: " + itemName,
    buyerUuid,
    null,           // authorizer - null unless shop account requires it
    "ChestShop",    // plugin attribution
    dedup
);

try {
    long txnId = treasury.transfer(req);
    // Success — dispense items
} catch (SecurityException e) {
    // Authorization required but not provided
} catch (IllegalStateException e) {
    // Insufficient funds (race condition between check and transfer)
} catch (IllegalArgumentException e) {
    // Account not found
}
```

**Sell flow** (player sells item to shop): Same pattern but `fromAccountId` is the shop account and `toAccountId` is the buyer's personal account. Check `hasFunds` on the shop account.

### 5. Balance display

When showing balances (e.g., in messages to the player):

```java
BigDecimal balance = treasury.getBalanceByAccountId(accountId);
String formatted = treasury.formatAmount(balance);
// e.g. "10,000.00"
String currency = balance.compareTo(BigDecimal.ONE) == 0
    ? treasury.getCurrencyNameSingular()
    : treasury.getCurrencyNamePlural();
```

### 6. Authorization support

If a business account has `requiresAuthorization = true`, transfers involving it need an `authorizer` UUID in the TransferRequest. This UUID must belong to someone in the account's authorizer list. The player operating the shop should be checked:

```java
Account shopAccount = treasury.getAccountById(shopAccountId);
if (shopAccount.isRequiresAuthorization()) {
    // Verify the interacting player is an authorizer
    List<AccountMember> authorizers = treasury.getAuthorizers(shopAccountId);
    boolean isAuthorized = authorizers.stream()
        .anyMatch(a -> a.getMemberUuid().equals(playerUuid));
    if (!isAuthorized) {
        // Deny
        return;
    }
    // Pass playerUuid as the authorizer in the TransferRequest
}
```

### 7. Error handling

TreasuryApi methods throw:
- `IllegalArgumentException` — account not found, invalid parameters
- `IllegalStateException` — insufficient funds, missing balance rows
- `SecurityException` — authorizer required but missing or not permitted
- `NullPointerException` — null required parameters

Catch these in the transaction flow and translate to user-friendly messages.

### 8. Backwards compatibility

Keep the existing Vault-based code path as a fallback if Treasury is not present (softdepend). Use an interface or strategy pattern:

```java
interface EconomyHandler {
    boolean hasFunds(UUID playerUuid, BigDecimal amount);
    boolean transfer(UUID from, UUID to, BigDecimal amount, ...);
    // etc.
}

class TreasuryEconomyHandler implements EconomyHandler { ... }
class VaultEconomyHandler implements EconomyHandler { ... }
```

Select the handler based on which plugins are available, preferring Treasury.
