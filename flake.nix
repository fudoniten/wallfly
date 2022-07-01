{
  description = "WallFly presence monitor..";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-22.05";
    utils.url = "github:numtide/flake-utils";
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, clj-nix, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages."${system}";
        cljpkgs = clj-nix.packages."${system}";
        update-deps = pkgs.writeShellScriptBin "update-deps.sh" ''
          ${clj-nix.packages."${system}".deps-lock}/bin/deps-lock
        '';
      in {
        packages = {
          wallfly = cljpkgs.mkCljBin {
            projectSrc = ./.;
            name = "org.fudo/wallfly";
            main-ns = "wallfly.core";
            jdkRunner = pkgs.jdk17_headless;
          };
        };

        defaultPackage = self.packages."${system}".wallfly;

        devShell =
          pkgs.mkShell { buildInputs = with pkgs; [ clojure update-deps ]; };
      }) // {
        overlay = final: prev: {
          inherit (self.packages."${prev.system}") wallfly;
        };

        nixosModule = import ./module.nix;
      };
}
