{
  description = "Nexus Server";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-22.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "git+https://git.fudo.org/fudo-public/nix-helpers.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, ... }:
    utils.eachDefaultSystem (system:
      let pkgs = import nixpkgs { inherit system; };
      in {
        packages = rec {
          default = nexus-server;
          nexus-server = helpers.packages."${system}".mkClojureBin {
            name = "org.fudo/nexus-server";
            primaryNamespace = "nexus.server.cli";
          };
        };

        devShells = rec {
          default = update-deps;
          update-deps = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}";
              [ updateClojureDeps ];
          };
        };
      });
}