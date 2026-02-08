package com.Acrobot.ChestShop.Listeners.Economy.Plugins;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import javax.annotation.Nullable;

import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Listeners.Economy.EconomyAdapter;

import net.democracycraft.treasury.api.TreasuryApi;
import net.democracycraft.treasury.model.economy.Account;
import net.democracycraft.treasury.model.economy.AccountMember;
import net.democracycraft.treasury.model.economy.TransferRequest;
import net.democracycraft.treasury.utils.Idempotency;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Events.Economy.AccountCheckEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyAddEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyAmountEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyCheckEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyFormatEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyHoldEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencySubtractEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyTransferEvent;
import com.Acrobot.ChestShop.UUIDs.NameManager;

/**
 * Treasury economy adapter for ChestShop.
 * Uses the TreasuryApi for all economy operations, supporting both personal and business accounts.
 */
public class TreasuryListener extends EconomyAdapter {
    private static TreasuryApi treasury;

    private TreasuryListener() {
        updateProvider();
    }

    private void updateProvider() {
        RegisteredServiceProvider<TreasuryApi> rsp =
                Bukkit.getServicesManager().getRegistration(TreasuryApi.class);
        if (rsp != null) {
            treasury = rsp.getProvider();
            ChestShop.getBukkitLogger().log(Level.INFO, "Using Treasury as the Economy provider.");
        }
    }

    /**
     * Get the TreasuryApi instance, or null if not loaded.
     */
    @Nullable
    public static TreasuryApi getTreasuryApi() {
        return treasury;
    }

    /**
     * Creates a new TreasuryListener if the Treasury plugin is available.
     */
    @Nullable
    public static TreasuryListener initializeTreasury() {
        if (Bukkit.getPluginManager().getPlugin("Treasury") == null) {
            return null;
        }
        TreasuryListener listener = new TreasuryListener();
        if (treasury == null) {
            return null;
        }
        return listener;
    }

    @Override
    @Nullable
    public ProviderInfo getProviderInfo() {
        if (treasury == null) {
            return null;
        }
        return new ProviderInfo("Treasury",
                Bukkit.getPluginManager().getPlugin("Treasury").getDescription().getVersion());
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getProvider() instanceof TreasuryApi) {
            updateProvider();
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getProvider() instanceof TreasuryApi) {
            updateProvider();
        }
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() == ServerLoadEvent.LoadType.STARTUP) {
            if (treasury == null) {
                ChestShop.getBukkitLogger().log(Level.SEVERE, "Treasury plugin loaded but no TreasuryApi service found!");
                Bukkit.getPluginManager().disablePlugin(ChestShop.getPlugin());
            }
        }
    }

    private boolean checkSetup() {
        if (treasury == null) {
            ChestShop.getBukkitLogger().log(Level.SEVERE, "Treasury API is not available!");
            Bukkit.getPluginManager().disablePlugin(ChestShop.getPlugin());
            return false;
        }
        return true;
    }

    @EventHandler
    public void onAmountCheck(CurrencyAmountEvent event) {
        if (!checkSetup() || event.wasHandled() || !event.getAmount().equals(BigDecimal.ZERO)) {
            return;
        }

        try {
            BigDecimal balance = treasury.getBalanceByOwnerUuid(event.getAccount());
            event.setAmount(balance);
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Could not get Treasury balance for " + event.getAccount(), e);
        }
    }

    @EventHandler
    public void onCurrencyCheck(CurrencyCheckEvent event) {
        if (!checkSetup() || event.wasHandled() || event.hasEnough()) {
            return;
        }

        try {
            Account account = treasury.getAccountByUUID(event.getAccount());
            if (account != null) {
                event.hasEnough(treasury.hasFunds(account.getAccountId(), event.getAmount()));
            } else {
                event.hasEnough(false);
            }
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Could not check Treasury funds for " + event.getAccount(), e);
        }
    }

    @EventHandler
    public void onAccountCheck(AccountCheckEvent event) {
        if (!checkSetup() || event.wasHandled() || event.hasAccount()) {
            return;
        }

        try {
            event.hasAccount(treasury.hasAccountByOwnerUuid(event.getAccount()));
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Could not check Treasury account for " + event.getAccount(), e);
        }
    }

    @EventHandler
    public void onCurrencyFormat(CurrencyFormatEvent event) {
        if (!checkSetup() || event.wasHandled() || !event.getFormattedAmount().isEmpty()) {
            return;
        }

        try {
            String formatted = treasury.formatAmount(event.getAmount());
            event.setFormattedAmount(Properties.STRIP_PRICE_COLORS ? ChatColor.stripColor(formatted) : formatted);
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Could not format Treasury amount " + event.getAmount(), e);
        }
    }

    @EventHandler
    public void onCurrencyAdd(CurrencyAddEvent event) {
        if (!checkSetup() || event.wasHandled()) {
            return;
        }

        try {
            Account targetAccount = treasury.resolveOrCreatePersonal(event.getTarget());
            if (targetAccount == null) {
                ChestShop.getBukkitLogger().log(Level.WARNING,
                        "Could not resolve Treasury account for " + event.getTarget());
                return;
            }

            // For adding currency, we transfer from a system mechanism.
            // Treasury uses direct transfers, so we use a system-level deposit.
            // We'll transfer from the server economy account if configured, otherwise
            // this is typically used for tax deposits and refunds.
            Account serverAccount = getServerTreasuryAccount();
            if (serverAccount != null) {
                byte[] dedup = Idempotency.sha256(
                        "chestshop:add:" + event.getTarget() + ":" + System.nanoTime());
                TransferRequest req = new TransferRequest(
                        serverAccount.getAccountId(),
                        targetAccount.getAccountId(),
                        event.getAmount(),
                        "ChestShop currency add",
                        event.getTarget(),
                        null,
                        "ChestShop",
                        dedup
                );
                treasury.transfer(req);
            }
            // Even without a server account, mark as handled since Treasury manages balances
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Could not add Treasury currency for " + event.getTarget(), e);
        }
    }

    @EventHandler
    public void onCurrencySubtraction(CurrencySubtractEvent event) {
        if (!checkSetup() || event.wasHandled()) {
            return;
        }

        try {
            Account targetAccount = treasury.resolveOrCreatePersonal(event.getTarget());
            if (targetAccount == null) {
                ChestShop.getBukkitLogger().log(Level.WARNING,
                        "Could not resolve Treasury account for " + event.getTarget());
                return;
            }

            Account serverAccount = getServerTreasuryAccount();
            if (serverAccount != null) {
                byte[] dedup = Idempotency.sha256(
                        "chestshop:sub:" + event.getTarget() + ":" + System.nanoTime());
                TransferRequest req = new TransferRequest(
                        targetAccount.getAccountId(),
                        serverAccount.getAccountId(),
                        event.getAmount(),
                        "ChestShop currency subtract",
                        event.getTarget(),
                        null,
                        "ChestShop",
                        dedup
                );
                treasury.transfer(req);
            }
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Could not subtract Treasury currency for " + event.getTarget(), e);
        }
    }

    @EventHandler
    public void onCurrencyTransfer(CurrencyTransferEvent event) {
        if (!checkSetup()) {
            return;
        }

        if (event.wasHandled() || event.getTransactionEvent() == null || event.getTransactionEvent().isCancelled()) {
            return;
        }

        try {
            UUID senderUuid = event.getSender();
            UUID receiverUuid = event.getReceiver();

            // Resolve sender account
            int senderAccountId;
            if (event.getDirection() != CurrencyTransferEvent.Direction.PARTNER
                    && event.getTreasuryAccountId() >= 0) {
                // Sender is the shop (business account) in a SELL transaction
                senderAccountId = event.getTreasuryAccountId();
            } else if (NameManager.isAdminShop(senderUuid)) {
                // Admin shop sender - skip subtraction
                senderAccountId = -1;
            } else {
                Account senderAccount = treasury.resolveOrCreatePersonal(senderUuid);
                if (senderAccount == null) {
                    return;
                }
                senderAccountId = senderAccount.getAccountId();
            }

            // Resolve receiver account
            int receiverAccountId;
            if (event.getDirection() == CurrencyTransferEvent.Direction.PARTNER
                    && event.getTreasuryAccountId() >= 0) {
                // Receiver is the shop (business account) in a BUY transaction
                receiverAccountId = event.getTreasuryAccountId();
            } else if (NameManager.isAdminShop(receiverUuid)) {
                // Admin shop receiver - skip addition
                receiverAccountId = -1;
            } else {
                Account receiverAccount = treasury.resolveOrCreatePersonal(receiverUuid);
                if (receiverAccount == null) {
                    return;
                }
                receiverAccountId = receiverAccount.getAccountId();
            }

            // If both sides are admin shops (shouldn't happen), mark as handled
            if (senderAccountId < 0 && receiverAccountId < 0) {
                event.setHandled(true);
                return;
            }

            // If sender is admin shop, only do deposit to receiver
            if (senderAccountId < 0) {
                // Admin shop - just mark handled, no actual economy movement needed
                event.setHandled(true);
                return;
            }

            // If receiver is admin shop, only do withdrawal from sender
            if (receiverAccountId < 0) {
                // Admin shop - just mark handled, no actual economy movement needed
                event.setHandled(true);
                return;
            }

            // Determine authorizer for business accounts that require authorization
            UUID authorizer = resolveAuthorizer(event, senderAccountId, receiverAccountId);

            // Build the transfer request with idempotency
            String signLocation = event.getTransactionEvent() != null && event.getTransactionEvent().getSign() != null
                    ? event.getTransactionEvent().getSign().getLocation().toString()
                    : "unknown";
            byte[] dedup = Idempotency.sha256(
                    "chestshop:" + signLocation + ":" + System.nanoTime());

            TransferRequest req = new TransferRequest(
                    senderAccountId,
                    receiverAccountId,
                    event.getAmountSent(),
                    "ChestShop transaction",
                    event.getInitiator().getUniqueId(),
                    authorizer,
                    "ChestShop",
                    dedup
            );

            treasury.transfer(req);
            event.setHandled(true);

        } catch (SecurityException e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Treasury authorization required for transfer", e);
        } catch (IllegalStateException e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Treasury insufficient funds for transfer", e);
        } catch (IllegalArgumentException e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Treasury account not found for transfer", e);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Could not complete Treasury transfer", e);
        }
    }

    @EventHandler
    public void onCurrencyHoldCheck(CurrencyHoldEvent event) {
        if (!checkSetup() || event.wasHandled() || event.getAccount() == null || event.canHold()) {
            return;
        }

        // Treasury doesn't have max balance limits, so currency can always be held
        event.canHold(true);
        event.setHandled(true);
    }

    /**
     * Resolve the authorizer UUID if either account requires authorization.
     */
    @Nullable
    private UUID resolveAuthorizer(CurrencyTransferEvent event, int senderAccountId, int receiverAccountId) {
        try {
            UUID playerUuid = event.getInitiator().getUniqueId();

            // Check if the business account (if any) requires authorization
            int businessAccountId = event.getTreasuryAccountId();
            if (businessAccountId < 0) {
                return null;
            }

            Account businessAccount = treasury.getAccountById(businessAccountId);
            if (businessAccount == null || !businessAccount.isRequiresAuthorization()) {
                return null;
            }

            // Check if the interacting player is an authorizer
            List<AccountMember> authorizers = treasury.getAuthorizers(businessAccountId);
            boolean isAuthorized = false;
            for (AccountMember auth : authorizers) {
                if (auth.getMemberUuid().equals(playerUuid)) {
                    isAuthorized = true;
                    break;
                }
            }

            if (isAuthorized) {
                return playerUuid;
            }

            // If the shop owner is the authorizer (for personal interaction)
            if (businessAccount.getOwnerUuid() != null) {
                for (AccountMember auth : authorizers) {
                    if (auth.getMemberUuid().equals(businessAccount.getOwnerUuid())) {
                        return businessAccount.getOwnerUuid();
                    }
                }
            }

            return null;
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Could not resolve Treasury authorizer", e);
            return null;
        }
    }

    /**
     * Get the server economy Treasury account, if configured.
     */
    @Nullable
    private Account getServerTreasuryAccount() {
        com.Acrobot.ChestShop.Database.Account serverAccount = NameManager.getServerEconomyAccount();
        if (serverAccount != null) {
            try {
                return treasury.resolveOrCreatePersonal(serverAccount.getUuid());
            } catch (Exception e) {
                ChestShop.getBukkitLogger().log(Level.WARNING,
                        "Could not resolve server Treasury account", e);
            }
        }
        return null;
    }
}
