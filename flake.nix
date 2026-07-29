{
  description = "Minecraft (Paper) server with a self-service whitelist web frontend";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }: {
    nixosModules.default = import ./module.nix { inherit self; };
  };
}
