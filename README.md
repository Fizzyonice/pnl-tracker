# PnL Tracker (DonutSMP, Fabric 1.21.10)

HUD overlay in the top-right that tracks your /pay profit & loss:

- **PROFIT / LOSS** – big net number (green when up, red when down), plus received, sent, payment count, and your balance once you run `/bal`
- **PLAYER NETS** – how much you're up or down with each player
- **RECENT** – your last 5 payments

It reads these DonutSMP messages:
- `<name> paid you $ <amount>` → counts as profit
- `You paid <name> $ <amount>` → counts as loss
- `You have $ <amount>` (from `/bal`) → sets your balance

Only server messages count, so someone typing a fake "paid you" in chat won't mess up your numbers.

## Commands
- `/pnl` – show your net in chat
- `/pnl reset` – start a fresh session (zero everything)
- `/pnl toggle` – hide/show the HUD

Stats save to `.minecraft/config/pnltracker.json`, so they survive restarts.

## Build the .jar

**Easiest (no setup):** push this folder to a GitHub repo → Actions tab → `build` runs automatically → download `pnl-tracker-jar`.

**On your PC:** install Java 21, open a terminal in this folder, run:
```
gradlew build        (Windows)
./gradlew build      (Mac/Linux)
```
The jar lands in `build/libs/`. Put it in your mods folder along with Fabric API.

If the build complains about a version, grab the current 1.21.10 values from https://fabricmc.net/develop/ and update `gradle.properties`.

> Note: Dawn Client may not load outside mods. If it doesn't, use the normal Fabric launcher profile for 1.21.10.
