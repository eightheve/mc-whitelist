{ self }:
{
  config,
  lib,
  pkgs,
  ...
}:
let
  cfg = config.services.mc-whitelist;

  backupScript = pkgs.writeShellScript "mc-whitelist-backup" ''
    set -euo pipefail

    fifo=/run/minecraft-server.stdin
    dataDir=${lib.escapeShellArg cfg.dataDir}
    backupDir=${lib.escapeShellArg cfg.backupDir}

    send() {
      if [ -p "$fifo" ]; then
        echo "$1" > "$fifo" || true
      fi
    }

    send "say §eServer restarting in 5 minutes for daily backup!"
    sleep ${toString cfg.backupWarningSeconds}

    echo "Stopping minecraft-server..."
    systemctl stop minecraft-server

    ts=$(date +%Y%m%d-%H%M%S)
    mkdir -p "$backupDir"

    targets=()
    for d in "$dataDir"/world*; do
      [ -d "$d" ] && targets+=("$(basename "$d")")
    done

    if [ ''${#targets[@]} -gt 0 ]; then
      echo "Backing up: ''${targets[*]}"
      tar -C "$dataDir" -cf - "''${targets[@]}" | zstd -T0 -o "$backupDir/mc-$ts.tar.zst"
    else
      echo "No world directories found; backing up nothing."
    fi

    # Prune: keep the newest ${toString cfg.backupRetention} backups
    ls -1t "$backupDir"/mc-*.tar.zst 2>/dev/null | tail -n +${toString (cfg.backupRetention + 1)} | xargs -r rm -f

    echo "Starting minecraft-server..."
    systemctl start minecraft-server
    echo "Backup done."
  '';
in
{
  options.services.mc-whitelist = {
    enable = lib.mkEnableOption "Minecraft (Paper) server with self-service whitelist site";

    serverPackage = lib.mkOption {
      type = lib.types.package;
      default = pkgs.minecraftServers.vanilla;
      description = "Minecraft server package (vanilla by default).";
    };

    dataDir = lib.mkOption {
      type = lib.types.path;
      default = "/var/lib/minecraft";
      description = "Minecraft server data directory.";
    };

    user = lib.mkOption {
      type = lib.types.str;
      default = "mc-whitelist";
      description = "User the whitelist web app runs as.";
    };

    web = {
      host = lib.mkOption {
        type = lib.types.str;
        default = "0.0.0.0";
        description = "Address the web app listens on.";
      };

      port = lib.mkOption {
        type = lib.types.port;
        default = 25566;
        description = "Port the web app listens on.";
      };

      serverName = lib.mkOption {
        type = lib.types.str;
        default = "mc.doppel.moe";
        description = "Public name shown on the site and used as the connection hint.";
      };
    };

    jvmOpts = lib.mkOption {
      type = lib.types.str;
      default = "-Xms4G -Xmx8G -Djava.net.preferIPv4Stack=true";
      description = "JVM options for the Minecraft server.";
    };

    backupDir = lib.mkOption {
      type = lib.types.str;
      default = "/srv/data/backups/minecraft";
      description = "Directory for world backups.";
    };

    backupRetention = lib.mkOption {
      type = lib.types.int;
      default = 7;
      description = "Number of most recent backups to keep.";
    };

    backupWarningSeconds = lib.mkOption {
      type = lib.types.int;
      default = 300;
      description = "How long after the in-game warning before the server stops for backup.";
    };

    backupTime = lib.mkOption {
      type = lib.types.str;
      default = "*-*-* 05:00:00";
      description = "systemd OnCalendar expression for daily backups.";
    };
  };

  config = lib.mkIf cfg.enable {
    users.users.${cfg.user} = {
      isSystemUser = true;
      group = "minecraft";
      home = "/var/lib/mc-whitelist";
      createHome = true;
    };

    services.minecraft-server = {
      enable = true;
      eula = true;
      declarative = false;
      package = cfg.serverPackage;
      inherit (cfg) dataDir jvmOpts;
      openFirewall = false;
    };

    # The web app (group: minecraft) must be able to read the whitelist and
    # world data produced by the server.
    systemd.services.minecraft-server.serviceConfig.UMask = lib.mkForce "0007";

    systemd.services.mc-whitelist = {
      description = "Minecraft self-service whitelist web app";
      after = [ "network-online.target" ];
      wants = [ "network-online.target" ];
      wantedBy = [ "multi-user.target" ];

      serviceConfig = {
        Type = "simple";
        User = cfg.user;
        Group = "minecraft";
        # Clojure source is read straight out of the flake; maven caches
        # dependencies under the service user's home on first start.
        WorkingDirectory = "${self}";
        Environment = [
          "HOST=${cfg.web.host}"
          "PORT=${toString cfg.web.port}"
          "MC_SERVER_NAME=${cfg.web.serverName}"
          "MC_FIFO=/run/minecraft-server.stdin"
          "MC_WHITELIST_JSON=${cfg.dataDir}/whitelist.json"
        ];
        ExecStart = "${pkgs.clojure}/bin/clojure -M:run";
        Restart = "on-failure";
        RestartSec = 5;
      };
    };

    systemd.services.minecraft-backup = {
      description = "Minecraft world backup (with restart)";
      path = [ pkgs.zstd pkgs.gnutar pkgs.coreutils pkgs.findutils pkgs.gnugrep ];
      serviceConfig = {
        Type = "oneshot";
        User = "root";
        ExecStart = backupScript;
      };
    };

    systemd.timers.minecraft-backup = {
      description = "Daily Minecraft backup + restart";
      wantedBy = [ "timers.target" ];
      timerConfig = {
        OnCalendar = cfg.backupTime;
        Persistent = true;
      };
    };

    systemd.tmpfiles.rules = [
      "d ${cfg.backupDir} 0755 root root -"
    ];

    # The web app is proxied to by nginx on KAZOOIE over WireGuard, so its
    # port must be reachable through the firewall.
    networking.firewall.allowedTCPPorts = [ cfg.web.port ];
  };
}
