{
  description = "WallFly presence monitor.";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-25.11";
    utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, utils, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages."${system}";
        gs = import nixpkgs { inherit system; };

      in {
        packages = {
          wallfly = pkgs.stdenv.mkDerivation {
            pname = "wallfly";
            version = "0.1";
            src = ./.;

            buildInputs = [ pkgs.babashka ];

            installPhase = ''
              mkdir -p $out/bin
              cp -r src $out/
              cat > $out/bin/wallfly <<EOF
              #!${pkgs.bash}/bin/bash
              exec ${pkgs.babashka}/bin/bb $out/src/wallfly/core.clj "\$@"
              EOF
              chmod +x $out/bin/wallfly
            '';
          };
        };

        defaultPackage = self.packages."${system}".wallfly;
      }) // {
        overlay = final: prev: {
          inherit (self.packages."${prev.system}") wallfly;
        };

        nixosModule = import ./module.nix self.overlay;
      };
}
