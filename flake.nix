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
        # Driver and browser revisions come from the same locked nixpkgs input.
        playwrightDriver = newPkgs.playwright-driver;
      in {
        devShells.default = newPkgs.mkShell {
          nativeBuildInputs = with newPkgs; [
            sbt
            jdk
            # Needed for Scala.js tests (sbt-scalajs uses Node.js as a JS runtime).
            nodejs
          ];
        };
        # Native-browser runs of the Scala.js DOM fixture (scripts/test-dom-fixture.sh).
        # It keeps the default build tools, because the runner links the fixture
        # first, and adds GNU timeout plus the Playwright driver with its matching
        # Chromium/WebKit bundle. Nothing is downloaded at run time.
        devShells.browser = newPkgs.mkShell {
          nativeBuildInputs = config.devShells.default.nativeBuildInputs
            ++ (with newPkgs; [ bash coreutils ]);
          PLAYWRIGHT_DRIVER_PATH = "${playwrightDriver}";
          PLAYWRIGHT_BROWSERS_PATH = "${playwrightDriver.browsers}";
          PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1";
          # Playwright checks /sbin/ldconfig, which cannot see Nix closures.
          # The packaged browsers carry patched runtime paths; native launch
          # and the fixture assertions remain the dependency/behavior gate.
          PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS = nixpkgs.lib.optionalString newPkgs.stdenv.isLinux "1";
          # The Linux WebKit wrapper needs an EGL vendor on headless machines.
          # The runner applies it only to its owned WebKit process.
          AVOCET_PLAYWRIGHT_EGL_VENDOR = nixpkgs.lib.optionalString newPkgs.stdenv.isLinux
            "${newPkgs.mesa}/share/glvnd/egl_vendor.d/50_mesa.json";
        };
      };

      flake = { };
    };
}
