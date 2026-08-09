# SinusAC

AI-powered anti-cheat for Minecraft Java and Bedrock servers. Combat and Fly telemetry is evaluated by the private SinusAI backend, while server owners can monitor results in the live dashboard.

[English](README.md) · [Русский](README.ru.md)

[![Release](https://img.shields.io/github/v/release/dtpsaa/SinusAC?label=release)](https://github.com/dtpsaa/SinusAC/releases/latest)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://adoptium.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-62b47a)](https://papermc.io/)
[![License](https://img.shields.io/badge/license-source--available-22d3c5)](LICENSE)

## Version 1.2.0

- All `/sinusac` subcommands now use one command implementation instead of separate packages and classes.
- Late Combat responses can no longer trigger alerts, VL, holograms or punishment after a player enters training mode.
- Source and configuration comments were removed for a cleaner project structure.

## What SinusAC is

SinusAC is the server plugin for the SinusAI anti-cheat platform. It collects compact combat and movement features and sends them to the SinusAI API for analysis. Detection logic and ML models remain on the private backend and are not included in this repository.

Main features:

- ML-powered Combat analysis
- Configurable Fly detection with an optional Bedrock-only mode
- Asynchronous HTTP processing without blocking the Minecraft tick thread
- Batched Fly requests to reduce server and network load
- Java and Bedrock player detection through Floodgate
- English and Russian localization
- Alerts, violation levels, setbacks and configurable punishment commands
- Per-license live dashboard with server status, checks, flags and cheat probability

## Important before you install

SinusAC requires an active subscription and activation key. Purchase access and obtain a key through the [SinusAI Telegram channel](https://t.me/sinusai).

The plugin connects to the official SinusAI API. A key is limited by its subscription term and licensed server count. Server identity is generated automatically from the public IP address and Minecraft port; `server.server-id` is not required.

Customer dashboard: [panel.sinusai.tech](https://panel.sinusai.tech/)

## Requirements

- Java 21 or newer
- Paper or a compatible Paper-based server running Minecraft 1.21+
- Internet access to the SinusAI API
- An active SinusAI activation key
- Floodgate when Bedrock player identification is required

## Installation

1. Download `SinusAC-<version>.jar` from [GitHub Releases](https://github.com/dtpsaa/SinusAC/releases/latest).
2. Place the JAR in the server's `plugins/` directory.
3. Start the server once to generate the configuration and locale files.
4. Open `plugins/SinusAC/config.yml` and set `license-key`.
5. Review the Combat, Fly and punishment settings before enabling automatic punishments.
6. Restart the server normally.

Do not use PlugMan or similar tools to replace the plugin at runtime. Use `/sinusac reload` for configuration changes and a normal server restart when updating the JAR.

## Configuration files

- `config.yml` — API connection, license, data collection, Combat and Fly settings
- `locale/en.yml` — English messages
- `locale/ru.yml` — Russian messages

English is the default locale. Set `locale: "ru"` in `config.yml` to use Russian.

Fly checks can be enabled or disabled through `checks.fly.enabled`. Keep `checks.fly.bedrock-only: true` to analyze only Bedrock players, or set it to `false` to include Java players.

## Main commands

| Command | Purpose |
| --- | --- |
| `/sinusac status` | Show API, license, locale and check status |
| `/sinusac alerts` | Toggle detection alerts |
| `/sinusac holo` | Toggle personal player-analysis holograms |
| `/sinusac check <player>` | Analyze the collected session manually |
| `/sinusac sessions` | List active analysis sessions |
| `/sinusac reload` | Reload configuration and locale files |

The `/sac` alias is also available.

## Permissions

| Permission | Purpose | Default |
| --- | --- | --- |
| `sinusac.admin` | Full command access | OP |
| `sinusac.alerts` | Receive detection alerts | OP |
| `sinusac.holo` | Use personal holograms | OP |
| `anticheat.bypass` | Bypass all SinusAC checks | Nobody |

## Building from source

```bash
git clone https://github.com/dtpsaa/SinusAC.git
cd SinusAC
mvn clean package
```

The compiled plugin will be written to:

```text
target/SinusAC-<version>.jar
```

Building the source does not grant API access. A valid activation key is still required to use the plugin.

## Help and bug reports

- Activation keys, purchases and support: [t.me/sinusai](https://t.me/sinusai)
- Dashboard: [panel.sinusai.tech](https://panel.sinusai.tech/)
- Bug reports: [GitHub Issues](https://github.com/dtpsaa/SinusAC/issues)

When reporting a bug, include the Minecraft version, server software, Java version, SinusAC version, relevant configuration values and the complete error stack trace. Never publish your activation key.

## License

SinusAC is source-available software, not open-source software. Use of the plugin requires a valid SinusAI subscription and is governed by the [SinusAI Source-Available License](LICENSE). Redistribution, resale, sublicensing and bypassing activation or subscription restrictions are prohibited.

Copyright © 2026 Individual Entrepreneur Alexander Igorevich Tsarev. SinusAI and SinusAC are owned by the right holder.
