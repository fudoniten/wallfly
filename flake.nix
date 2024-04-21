{
  description = "WallFly presence monitor.";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-23.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "git+https://fudo.dev/public/nix-helpers.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fudo-clojure.url = "git+https://fudo.dev/public/fudo-clojure.git";
  };

  outputs = { self, nixpkgs, utils, helpers, fudo-clojure, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages."${system}";
        gs = import nixpkgs { inherit system; };

        cljLibs = {
          "org.fudo/fudo-clojure" =
            fudo-clojure.packages."${system}".fudo-clojure;
        };
      in {
        packages = {
          wallfly = helpers.packages."${system}".mkClojureBin {
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
            buildInputs = with helpers.packages."${system}";
              [ (updateClojureDeps cljLibs) ];
          };
        };
      }) // {
        overlay = final: prev: {
          inherit (self.packages."${prev.system}") wallfly;
        };

        nixosModule = import ./module.nix self.overlay;
      };
}
