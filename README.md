# Event Manager
Event Manager is a server-side Fabric mod for hosting organized Minecraft events with:

- Role-based permissions
- Pre-event holding control
- Role spawns
- Editable role kits
- Randomized names and skins
- In-game admin commands and role configuration

Use it to contain players before an event, assign them roles, equip them with kits, and release them when the round starts.

## Features

- Server-side only Fabric mod
- Custom `void_prison` holding dimension for pre-event containment
- Event phases: `CLOSED`, `RUNNING`, `ENDING`
- Role-based permissions for:
  - Breaking blocks
  - Placing blocks
  - Using items
  - Opening blocks
  - Using containers
  - Interacting with entities
  - PvP
  - Picking up items
  - Dropping items
- Per-role spawn points
- Per-role kits
- Default kit support
- Optional role bypass flow
- Optional random name and skin assignment
- Identity pool import and reload commands
- In-game role configuration GUI

## How It Works

When the mod is loaded, it manages players through an event session system.

- `CLOSED`: eligible players are contained in the holding dimension
- `RUNNING`: contained players are released, teleported to role spawns, and given their role kits
- `ENDING`: cleanup phase before returning to `CLOSED`

Roles decide what a player is allowed to do and what kit they receive.

## Kits

Each role has an editable kit.

- Opening `Change Kit` from the role configure menu lets you place the items for that role
- When the role is applied during release, the player's inventory is cleared first
- The saved role kit is then inserted into the player's inventory

Default role kit:

- Wooden sword
- Shield
- Full leather armor

## Commands

Main command: `/event`

Core flow:

- `/event start`
- `/event end`
- `/event status`

Debug:

- `/event debug participants`
- `/event debug contained`
- `/event debug identity <player>`
- `/event debug release <player>`
- `/event debug roles`

Bypass:

- `/event bypass add <player>`
- `/event bypass remove <player>`
- `/event bypass list`

Autojoin:

- `/event autojoin add <player>`
- `/event autojoin remove <player>`
- `/event autojoin list`

Identities:

- `/event identities list`
- `/event identities import <url>`
- `/event identities reload`

Config:

- `/event config adminAutoJoin <true|false>`
- `/event config defaultRole <role>`

Roles:

- `/event roles create <name>`
- `/event roles list`
- `/event roles info <role>`
- `/event roles assign <player> <role>`
- `/event roles unassign <player>`
- `/event roles get <player>`
- `/event roles configure <role>`
- `/event roles setspawn <role>`
- `/event roles clearspawn <role>`
- `/event roles setbypassflow <role> <true|false>`

## Configuration

Main config file:

- `config/eventmanager.json`

Identity files:

- `config/eventmanager/identities/names.json`
- `config/eventmanager/identities/skins.json`
- `config/eventmanager/identities/namemc_request.json`

## Requirements

- Minecraft `1.21.11`
- Fabric Loader `0.18.4+`
- Fabric API `0.141.3+1.21.11`
- Java 21

## Notes

- This mod is server-side.
- Clients do not need the mod for the event logic itself.
- Identity randomization works best when the identity pool is properly populated.
- Role kits currently store item type and count.

## License

MIT.
