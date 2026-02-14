{
  description = "Flake for dev shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
  };

  outputs = inputs@{ nixpkgs, flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      perSystem = { config, self', inputs', pkgs, system, ... }:
      let
        # Scala 3 only; target JDK 21+.
        jdk21-overlay = self: super: {
          jdk = super.jdk21;
          jre = super.jdk21;
          sbt = super.sbt.override { jre = super.jdk21; };
        };
        newPkgs = import nixpkgs {
          inherit system;
          overlays = [ jdk21-overlay ];
        };
      in {
        devShells.default = newPkgs.mkShell {
          nativeBuildInputs = with newPkgs; [
            sbt
            jdk
            # Needed for Scala.js tests (sbt-scalajs uses Node.js as a JS runtime).
            nodejs
          ];
        };
      };

      flake = { };
    };
}
