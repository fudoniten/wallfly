wallfly-overlay:

{ config, lib, pkgs, ... }:

with lib;
let cfg = config.fudo.wallfly;

in {
  options.fudo.wallfly = with types; {
    enable =
      mkEnableOption "Enable WallFly presence monitor for users on this host.";

    location = mkOption {
      type = str;
      description = "Location (in Home Assistant) of this host.";
      default = "unknown";
    };

    mqtt = {
      broker-uri = mkOption {
        type = str;
        description = "URI of the MQTT broker.";
        example = "tcp://my-mqtt.host:1883";
      };

      username = mkOption {
        type = str;
        description = "Username with which to connect to the MQTT broker.";
        default = "wallfly";
      };

      password-file = mkOption {
        type = str;
        description = "Path to a file containing the MQTT user password.";
      };
    };

    time-to-idle = mkOption {
      type = int;
      description =
        "Number of seconds before considering the user idle on this host.";
      default = "900"; # 15 minutes
    };

    delay-time = mkOption {
      type = int;
      description =
        "Number of seconds to wait before polling for user activity.";
      default = 30;
    };
  };

  config = mkIf cfg.enable {
    nixpkgs.overlays = [ wallfly-overlay ];

    systemd.user.services.wallfly = {
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" ];
      serviceConfig = {
        ExecStart = pkgs.writeShellScript "launch-wallfly.sh" ''
          ${pkgs.wallfly}/bin/wallfly \
              --location=${cfg.location} \
              --mqtt-broker-uri=${cfg.mqtt.broker-uri} \
              --mqtt-username=${cfg.mqtt.username} \
              --mqtt-password-file=${cfg.mqtt.password-file} \
              --time-to-idle=${cfg.time-to-idle} \
              --delay-time=${cfg.delay-time}
        '';
        PrivateTmp = true;
        PrivateDevices = true;
        ProtectSystem = "strict";
        ProtectControlGroups = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectHostname = true;
        ProtectHome = true;
        ProtectClock = true;
        ProtectKernelLogs = true;
        Restart = "always";
        StandardOutput = "journal";
      };
    };
  };
}
