{
  description = "WallFly presence monitor.";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-25.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fudo-clojure.url = "github:fudoniten/fudo-clojure";
  };

  outputs = { self, nixpkgs, utils, helpers, fudo-clojure, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages."${system}";
        gs = import nixpkgs { inherit system; };

        cljLibs = {
          "org.fudo/fudo-clojure" =
            fudo-clojure.packages."${system}".fudo-clojure.preppedSrc;
        };
      in {
        packages = {
          wallfly = helpers.legacyPackages."${system}".mkClojureBin {
            projectSrc = ./.;
            name = "org.fudo/wallfly";
            primaryNamespace = "wallfly.core";
            src = ./.;
            inherit cljLibs;
          };
        };

        defaultPackage = self.packages."${system}".wallfly;

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.legacyPackages."${system}";
              [ (updateClojureDeps { deps = cljLibs; }) ];
          };
        };
      }) // {
        overlay = final: prev: {
          inherit (self.packages."${prev.system}") wallfly;
        };

        nixosModule = import ./module.nix self.overlay;
      };
}
