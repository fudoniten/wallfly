# WallFly 🪟

A presence detector for Home Assistant that monitors whether a user is actively using a computer.

WallFly runs on your desktop or laptop and polls the idle time using `xprintidle`, publishing a presence/occupancy status to your Home Assistant instance via MQTT. This allows Home Assistant to know when computers are in active use, enabling automations like:

- Turn off lights when nobody's working
- Skip notifications when you're AFK
- Adjust room climate based on occupancy
- Track when machines are idle for power management

## How It Works

1. **Idle Monitoring**: Polls `xprintidle` every N seconds to check user activity
2. **MQTT Publishing**: Sends presence state (`ON`/`OFF`) to your MQTT broker
3. **Auto-Configuration**: Automatically registers as a binary sensor in Home Assistant (MQTT Discovery)
4. **Graceful Offline**: Publishes a retained `OFF` message on startup (persists if the host goes down)

## Installation

### Via NixOS (Recommended)

Add to your `configuration.nix`:

```nix
# Enable the module
fudo.wallfly.enable = true;

# Configure for your setup
fudo.wallfly = {
  enable = true;
  location = "office";  # Location in Home Assistant
  mqtt.broker-uri = "tcp://mqtt.example.com:1883";
  mqtt.username = "wallfly";
  mqtt.password-file = "/run/agenix/wallfly-mqtt-password";  # Or any password file
  time-to-idle = 900;   # Seconds (15 minutes)
  delay-time = 30;      # Poll interval (seconds)
};
```

The module automatically:
- Builds the Clojure application
- Creates a systemd user service
- Manages startup and logging

### Manual Installation

Requires:
- Java Runtime (JVM)
- Clojure
- `xprintidle` (X11 idle time tool)
- Access to an MQTT broker

```bash
# Build
nix build .

# Run
./result/bin/wallfly \
  --location office \
  --mqtt-broker-uri tcp://mqtt.example.com:1883 \
  --mqtt-username wallfly \
  --mqtt-password-file /path/to/password \
  --time-to-idle 900 \
  --delay-time 30
```

## Configuration

### Required Options

| Option | Description | Example |
|--------|-------------|---------|
| `--location` / `WALLFLY_LOCATION` | Location name in Home Assistant | `"office"`, `"living-room"` |
| `--mqtt-broker-uri` / `WALLFLY_MQTT_BROKER_URI` | MQTT broker address | `tcp://mqtt:1883` |
| `--mqtt-username` / `WALLFLY_MQTT_USERNAME` | MQTT login username | `wallfly` |
| `--mqtt-password-file` / `WALLFLY_MQTT_PASSWORD_FILE` | File containing MQTT password | `/run/secrets/mqtt-pass` |
| `--time-to-idle` / `WALLFLY_TIME_TO_IDLE` | Seconds before marking as idle | `900` (15 min) |
| `--delay-time` / `WALLFLY_DELAY_TIME` | Polling interval (seconds) | `30` |

### Environment Variables

All options can be set via `WALLFLY_*` environment variables instead of command-line flags:

```bash
export WALLFLY_LOCATION="office"
export WALLFLY_MQTT_BROKER_URI="tcp://mqtt:1883"
export WALLFLY_MQTT_USERNAME="wallfly"
export WALLFLY_MQTT_PASSWORD_FILE="/run/secrets/mqtt-pass"
export WALLFLY_TIME_TO_IDLE="900"
export WALLFLY_DELAY_TIME="30"

wallfly  # Uses env vars if flags not provided
```

## Home Assistant Integration

Once running, WallFly automatically registers itself with Home Assistant via MQTT Discovery.

### What You Get

A binary sensor named: `binary_sensor.{username}_present_on_{hostname}`

**Attributes:**
- **State**: `ON` (active) or `OFF` (idle)
- **Device Name**: `{hostname} WallFly`
- **Location**: Automatically grouped by the `location` you configured
- **Icon**: Account check icon 🧑‍💼
- **Off Delay**: `time-to-idle` seconds (Home Assistant automatically switches to `OFF` after this period of inactivity)

### Example Automations

```yaml
automation:
  - alias: "Dim lights when office is idle"
    trigger:
      entity_id: binary_sensor.peter_present_on_workstation
      to: "off"
    action:
      service: light.turn_off
      target:
        entity_id: light.office
      data:
        transition: 300

  - alias: "Turn on desk lights when working"
    trigger:
      entity_id: binary_sensor.peter_present_on_workstation
      to: "on"
    action:
      service: light.turn_on
      target:
        entity_id: light.office
```

## Requirements

- **X11 Server**: Uses `xprintidle` to detect idle time (requires X11, not Wayland currently)
- **MQTT Broker**: Accessible from the host running WallFly
- **Home Assistant**: With MQTT integration enabled (or Mosquitto add-on)

## Development

### Testing

```bash
# Check for build errors
nix flake check

# Build the application
nix build .

# Run tests (if any)
nix build .#checks.x86_64-linux.clojureTests
```

### Build System

WallFly uses:
- **Language**: Clojure
- **Build**: Nix flakes + fudo-nix-helpers
- **MQTT Library**: Eclipse Paho MQTT (Java)
- **Utilities**: fudo-clojure (error handling, logging)

## Troubleshooting

### Not Connecting to MQTT

1. Check the password file exists and is readable:
   ```bash
   cat /run/secrets/mqtt-password
   ```

2. Verify MQTT broker is reachable:
   ```bash
   telnet mqtt.example.com 1883
   ```

3. Check systemd journal:
   ```bash
   journalctl -u wallfly.service --user -f
   ```

### Not Detecting Idle Time

- Ensure `xprintidle` is installed: `which xprintidle`
- Verify you're running X11 (not Wayland)
- Check that the service is running: `systemctl status --user wallfly.service`

### Sensor Not Appearing in Home Assistant

1. Ensure MQTT integration is enabled
2. Check Home Assistant MQTT logs
3. Verify the MQTT user has publish permissions on `homeassistant/#`
4. Restart the WallFly service to retrigger discovery

## License

Part of the Fudo project. See repository for license details.

## Related

- [Home Assistant MQTT Integration](https://www.home-assistant.io/integrations/mqtt/)
- [MQTT Discovery](https://www.home-assistant.io/integrations/mqtt/#mqtt-discovery)
- [xprintidle](https://linux.die.net/man/1/xprintidle) — X11 idle time tool
- [fudo-nix-helpers](https://github.com/fudoniten/fudo-nix-helpers) — Build utilities
