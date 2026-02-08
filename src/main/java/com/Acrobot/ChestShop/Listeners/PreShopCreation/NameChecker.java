package com.Acrobot.ChestShop.Listeners.PreShopCreation;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Database.Account;
import com.Acrobot.ChestShop.Events.AccountQueryEvent;
import com.Acrobot.ChestShop.Events.PreShopCreationEvent;
import com.Acrobot.ChestShop.Listeners.Economy.Plugins.TreasuryListener;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import com.Acrobot.ChestShop.UUIDs.NameManager;
import net.democracycraft.treasury.api.TreasuryApi;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.logging.Level;

import static com.Acrobot.ChestShop.Permission.OTHER_NAME_CREATE;
import static com.Acrobot.ChestShop.Signs.ChestShopSign.NAME_LINE;
import static com.Acrobot.ChestShop.Events.PreShopCreationEvent.CreationOutcome.UNKNOWN_PLAYER;

/**
 * @author Acrobot
 */
public class NameChecker implements Listener {

    @EventHandler(priority = EventPriority.LOW)
    public static void onPreShopCreation(PreShopCreationEvent event) {
        handleEvent(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public static void onPreShopCreationHighest(PreShopCreationEvent event) {
        handleEvent(event);
    }

    private static void handleEvent(PreShopCreationEvent event) {
        String name = ChestShopSign.getOwner(event.getSignLines());
        Player player = event.getPlayer();

        // Handle business account format (B:<accountId>)
        if (ChestShopSign.isBusinessAccount(name)) {
            handleBusinessAccount(event, name, player);
            return;
        }

        Account account = event.getOwnerAccount();
        if (account == null || !account.getShortName().equalsIgnoreCase(name)) {
            account = null;
            try {
                if (name.isEmpty() || !NameManager.canUseName(player, OTHER_NAME_CREATE, name)) {
                    account = NameManager.getOrCreateAccount(player);
                } else {
                    AccountQueryEvent accountQueryEvent = new AccountQueryEvent(name);
                    ChestShop.callEvent(accountQueryEvent);
                    account = accountQueryEvent.getAccount();
                    if (account == null) {
                        Player otherPlayer = ChestShop.getBukkitServer().getPlayer(name);
                        try {
                            if (otherPlayer != null) {
                                account = NameManager.getOrCreateAccount(otherPlayer);
                            } else {
                                account = NameManager.getOrCreateAccount(ChestShop.getBukkitServer().getOfflinePlayer(name));
                            }
                        } catch (IllegalArgumentException e) {
                            event.getPlayer().sendMessage(e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                ChestShop.getBukkitLogger().log(Level.SEVERE, "Error while trying to check account for name " + name + " with player " + player.getName(), e);
            }
        }
        event.setOwnerAccount(account);
        if (account != null) {
            event.setSignLine(NAME_LINE, account.getShortName());
        } else {
            event.setSignLine(NAME_LINE, "");
            event.setOutcome(UNKNOWN_PLAYER);
        }
    }

    private static void handleBusinessAccount(PreShopCreationEvent event, String name, Player player) {
        TreasuryApi treasury = TreasuryListener.getTreasuryApi();
        if (treasury == null) {
            // Treasury not loaded, can't create business account shops
            event.setSignLine(NAME_LINE, "");
            event.setOutcome(PreShopCreationEvent.CreationOutcome.BUSINESS_ACCOUNT_NOT_FOUND);
            return;
        }

        int accountId;
        try {
            accountId = ChestShopSign.getBusinessAccountId(name);
        } catch (NumberFormatException e) {
            event.setSignLine(NAME_LINE, "");
            event.setOutcome(PreShopCreationEvent.CreationOutcome.BUSINESS_ACCOUNT_NOT_FOUND);
            return;
        }

        try {
            // Verify the Treasury account exists
            if (!treasury.hasAccountByAccountId(accountId)) {
                event.setSignLine(NAME_LINE, "");
                event.setOutcome(PreShopCreationEvent.CreationOutcome.BUSINESS_ACCOUNT_NOT_FOUND);
                return;
            }

            // Verify the player has access to the business account
            if (!treasury.canAccessAccount(player.getUniqueId(), accountId)) {
                event.setSignLine(NAME_LINE, "");
                event.setOutcome(PreShopCreationEvent.CreationOutcome.NO_TREASURY_ACCOUNT_ACCESS);
                return;
            }

            // Get the Treasury account to find the owner UUID
            net.democracycraft.treasury.model.economy.Account treasuryAccount = treasury.getAccountById(accountId);
            if (treasuryAccount == null || treasuryAccount.getOwnerUuid() == null) {
                event.setSignLine(NAME_LINE, "");
                event.setOutcome(PreShopCreationEvent.CreationOutcome.BUSINESS_ACCOUNT_NOT_FOUND);
                return;
            }

            // Resolve the ChestShop Account from the Treasury account's owner UUID
            Account account = NameManager.getOrCreateAccount(
                    ChestShop.getBukkitServer().getOfflinePlayer(treasuryAccount.getOwnerUuid()));

            event.setOwnerAccount(account);
            event.setTreasuryAccountId(accountId);
            // Keep the B:<id> format on the sign so it's visible
            event.setSignLine(NAME_LINE, name);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.SEVERE,
                    "Error while checking Treasury business account " + name + " for player " + player.getName(), e);
            event.setSignLine(NAME_LINE, "");
            event.setOutcome(PreShopCreationEvent.CreationOutcome.BUSINESS_ACCOUNT_NOT_FOUND);
        }
    }
}
