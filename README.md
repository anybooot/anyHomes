<div align="center">

# ⌂ AnyHomes ; Premium Home Management

<p align="center">
  <img src="https://img.shields.io/badge/status-active-success?style=for-the-badge&color=00c6ff" alt="Status">
  <img src="https://img.shields.io/badge/version-v2.1.0-blue?style=for-the-badge&color=7b2ffc" alt="Version">
  <img src="https://img.shields.io/badge/Minecraft-1.21.x-orange?style=for-the-badge" alt="Minecraft">
  <img src="https://img.shields.io/badge/Java-21%2B-informational?style=for-the-badge" alt="Java">
</p>

<p align="center">
  <b>High-end, asynchronous home teleportation and management system built for modern Minecraft servers. YAML-driven menus, dynamic permissions, and zero-lag performance.</b>
</p>

</div>

---

### ⚡ Core Features

| Feature | Description |
| :--- | :--- |
| **⚡ Async Processing** | HikariCP connection pool + Paper's async chunk loading. Zero main-thread blocking. |
| **◈ YAML Menus** | Full control over layouts, slots, item actions, and pagination — all from config files. |
| **🔐 Dynamic Permissions** | Permission nodes are fully customizable in real-time via config.yml. |

---

### 01 ⚙ System Requirements

To run AnyHomes, your server must meet these specifications:

* **Java Version:** `Java 21+`
* **Game Version:** `Minecraft 1.21.x`[cite: 3]
* **Server Software:** `Paper / Purpur`[cite: 3] (Paper+ recommended for async teleportation).

> **Dependencies (Soft):**
> * **LuckPerms:** Required for group-based home limits via the `home-limit` meta key[cite: 3].
> * **PlaceholderAPI:** Use server-wide placeholders inside menus and messages[cite: 3].

---

### 02 ⌨ Commands & Permissions

AnyHomes uses a flexible command layout. All permission nodes are dynamic and can be customized inside `config.yml`[cite: 3].

| Command | Description | Default Permission | Type |
| :--- | :--- | :--- | :---: |
| `/home [name]` | Teleport to your home. Defaults to 'home'. | `anyhomes.home`[cite: 3] | 👤 Player |
| `/sethome [name]` | Set a home at your current location. | `anyhomes.sethome`[cite: 3] | 👤 Player |
| `/delhome <name>` | Delete a saved home with confirmation. | `anyhomes.delhome`[cite: 3] | 👤 Player |
| `/homes` | Open the homes GUI list with pagination. | `anyhomes.list`[cite: 3] | 👤 Player |
| `/homes <player>:[home]` | Teleport to a player's home, or list theirs. | `anyhomes.admin`[cite: 3] | 🛡️ Admin |
| `/sethomes <player>:<home>` | Set a home for another player at your location. | `anyhomes.admin`[cite: 3] | 🛡️ Admin |
| `/delhomes <player>:<home>` | Delete another player's home. | `anyhomes.admin`[cite: 3] | 🛡️ Admin |
| `/anyhomes reload` | Reload configs, messages, and menus. | `anyhomes.admin`[cite: 3] | 🛡️ Admin |

---

### 03 ◈ Placeholder System

AnyHomes dynamically replaces placeholders inside menus, messages, and chat outputs. Both local and PlaceholderAPI placeholders are supported[cite: 3].

#### Local Placeholders
<code>&lt;prefix&gt;</code> <ins>plugin prefix</ins> &nbsp;&bull;&nbsp; 
<code>&lt;home&gt;</code> <ins>home name</ins> &nbsp;&bull;&nbsp; 
<code>&lt;world&gt;</code> <ins>world name</ins> &nbsp;&bull;&nbsp; 
<code>&lt;coords&gt;</code> <ins>x, y, z</ins> &nbsp;&bull;&nbsp; 
<code>&lt;count&gt;</code> <ins>homes count</ins> &nbsp;&bull;&nbsp; 
<code>&lt;max&gt;</code> <ins>max homes</ins> &nbsp;&bull;&nbsp; 
<code>&lt;seconds&gt;</code> <ins>cooldown / warmup</ins> &nbsp;&bull;&nbsp; 
<code>&lt;player&gt;</code> <ins>target player</ins>[cite: 3]

#### PlaceholderAPI Integration
All GUI titles, item lore, chat messages, titles, and action bars support PlaceholderAPI placeholders like `%player_name%`, `%vault_eco_balance%`, or `%essentials_nickname%`[cite: 3].

---

### 04 ✦ Advanced Color System

AnyHomes supports all modern Minecraft color formats with an intelligent parser that handles hexadecimal gradients, MiniMessage, and legacy codes[cite: 3].

* **MiniMessage Gradients:** `<gradient:#00c6ff:#7b2ffc>Bold Gradient</gradient>`[cite: 3]
* **MiniMessage Hex:** `<#ffffff>White Text</#ffffff>`[cite: 3]
* **Legacy Codes:** `&7Gray` &middot; `&c&lBold Red`[cite: 3]
* **Raw Hex:** `#ff0000Red Text`[cite: 3]

---

### 05 ▣ YAML GUI & Pagination

Customize inventories directly in the `menus/` folder. Set rows, materials, player-head textures, item actions, and pagination rules[cite: 3].

By default, homes occupy slots `20–24`. When a player has more than 5 homes, the plugin automatically paginates, with arrow controls to browse pages[cite: 3].

```yaml
# Example from menus/homes.yml
rows: 5
home-slots:
  - 20
  - 21
  - 22
  - 23
  - 24
items:
  help:
    slot: 42
    material: COMPASS
    display-name: "<gradient:#00c6ff:#7b2ffc>Help & Commands</gradient>"
    action: OPEN_HELP
