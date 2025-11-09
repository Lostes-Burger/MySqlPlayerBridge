# 🌐 MySqlPlayerBridge

> **The FREE modern alternative to outdated sync plugins** – fully working on **Minecraft 1.21.\*** with true **cross-version** and **Folia** support!

---

## ❓ Why not use "[MySQL Player Data Bridge](https://www.spigotmc.org/resources/mysql-player-data-bridge.8117/)"?

The old **MySQL Player Data Bridge** plugin:
- ❌ Is no longer supported and has been broken since Minecraft **1.21**.
- 💸 Still costs money despite being **outdated** and **not working**.

**MySqlPlayerBridge** is your **completely FREE**, **modern**, and **fully functional** replacement – built for today’s servers and designed to work where the old one fails.

---

## 🚀 What makes this plugin unique?

### 🔄 Cross-Version Support
Sync player data between **different Minecraft versions**.
> Example: A server running **1.20.\*** can sync perfectly with another running **1.21.\***.  
> One database, one seamless player experience.

### ⚡ Folia Compatibility
Fully compatible with **Minecraft Folia**, ensuring **smooth performance** and **optimized async operations**.

### 🧩 NBT API Integration
Uses the **NBT API** for reliable and version-independent data transfer.

---

## 🧰 Key Features

### 🔁 **Cross-Server & Cross-Version Sync**
    - Inventory
    - Ender Chest
    - Armor
    - Location
    - Gamemode
    - Experience
    - Health
    - Saturation
    - Economy (via Vault)

### 🧠 **Fail-Safe Data Handling**  
  Automatic retries, optional player kick on repeated failure, and secure local backups.

### ⚙️ **Customizable Sync Modules**  
  Enable or disable exactly the data types you want to sync.

### ⏱️ **Automatic Sync Task**  
  Schedule periodic saves for all online players.

### 🧱 **No-Entry Protection**  
  Prevents overwriting important data from other servers.

---

## 🧩 Requirements

- 🗄️ **MySQL Database**
- 📦 **[NBT API](https://modrinth.com/plugin/nbtapi)**
- 💰 **[Vault](https://www.spigotmc.org/resources/vault.34315/)** *(optional, for economy sync)*
- ☕ **Java 21**

---
## 🔮 Future Plans

We’re not done yet — **MySqlPlayerBridge** is constantly evolving to cover even more aspects of the Minecraft experience!  
Here’s what’s coming soon:

- 🧓 **Legacy Serializer Option without NBTAPI plugin**  
  A simplified mode for smaller setups (drops Folia & cross-version support for easier configurations).

- 🧰 **Extended Sync Modules**  
  Planned additions include:
    - 🧪 **Active Potion Effects** – sync active potion effects across servers.
    - 🏆 **Advancements** – sync player achievements.
    - 📊 **Statistics** – ensure global tracking of minecraft statistics.
    - 🔧 **Commands** – New admin commands for manual intervention

These features are already in development and will be introduced step by step —  
bringing you **the most complete player sync solution** available for modern Minecraft servers.


---

## ✅ Free – Modern – Fully Functional
Download now and experience **true cross-server syncing** for **1.21.\***!

---

## ⚙️ Configs

<details>
<summary><b>config.yml</b></summary>


```yaml
# Plugin Prefix color codes and rgb supported
prefix: "§7[§9MPB§7]§r "
# See your installed version here. Please provide at support requests and always check for newest version.
version: ""

settings:
  # Change the root command (default /mpb <subcommand>)
  command-prefix: "mpb"
  # Should due to any reason the sync fail, the plugin will try again to save. Ff the sync fails again the Player will be kicked and their Data printed and Saved in a file in the plugin directory.
  kickPlayerOnSyncFail: true

  # Only important if you have an existing server with saved player data
  # Important: If you are deploying this plugin on an existing server with saved player data,
  # it can happen that the data from the wrong server is saved.
  # For example, a player has important data on server1 but none on server2 (e.g., a farm world).
  # If the player joins server1 first, everything is fine — the data is created in the database and transferred to server2.
  # However, if the player joins server2 first, before the data from server1 is saved,
  # the empty data will be saved and transferred back to server1, effectively deleting the original data.
  # To avoid this problem, you can enable "no-entry-protection" below.
  # Set it to true only on servers that should *not* be treated as the source of the default player data
  # (in this example: server2 = true, server1 = false).
  # If this option is enabled and a player joins without existing data (for all enabled sync modules),
  # the connection will be denied and no data will be saved.
  no-entry-protection: false

  # Permission needed to perform admin commands
  admin-permission: "mbp.admin"

sync:
  inventory: false
  enderChest: false
  amorSlots: false
  location: false
  gamemode: false
  exp: false
  health: false
  saturation: false
  # This plugin only supports Vault as Economy Manager.
  # Make sure Vault (https://www.spigotmc.org/resources/vault.34315/) is installed, before enabling this module.
  vaultEconomy: false

# Create an automatic running task which saves all online player's inventory every given time.
syncTask:
  # Delay in Seconds
  delay: 90
```
</details>

<details>
<summary><b>messages.yml</b></summary>

```yaml
# Message prefix can be changed in config.yml
# Restart to apply any changes

no-database-config-error: "§cEnter mysql credentials in the mysql.yml config. Restart after to use this plugin!"
sync-success: "§7Player data§a successfully§9 synced!"
sync-failed: "§cPlayer data could not be synced. Please contact staff or try joining again."
created-data: "§7Player data§a successfully §9created"
no-entry-protection-kick: "§cNo entry protection enabled.§7 You have no existing player data. You can't join this server before joining the parent server first and receiving your player data. If you think this is an error, please contact server staff."

# Commands
permission-error: "§cInsufficient permission to execute this command"
# Placeholder <all subcommands> -> {subcomands}
unknown-subcommand-error: "§cUnknown subcommand. Available: §7<{subcommands}>"
no-subcommand-error: "§cProvide a subcommand. Available: §7<{subcommands}>"

# Clear subcommand
clear-wrong-usage: "§cWrong usage! Use clear <player_name/*>"
clear-player-not-found: "§cPlayer not found. This player is not known. Deletion failed..."
```
</details>

<details>
<summary><b>mysql.yml</b></summary>

```yaml
# Input your Database credentials. MariaDB/MySQL databases (server) supported.

host: ""
port: 3306
database: ""
user: ""
password: ""

# If you change this with a full table of player data a new Table will be created and all data ignored (not deleted)!
main-table-name: "player_data"
```
</details>

README.md written by [ChatGPT](https://chatgpt.com/)