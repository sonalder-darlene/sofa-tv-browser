{
  description = "Sofa TV Browser: a lightweight, private web browser for Android TV and Google TV";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        # The Android SDK is unfree and ships under Google's SDK license.
        config = { allowUnfree = true; android_sdk.accept_license = true; };
      };
      buildToolsVersion = "36.1.0";
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeCmake = false;
        includeEmulator = false;
        includeNDK = false;
        includeSources = false;
        includeSystemImages = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
      # Android TV 14 emulator for testing (`nix develop .#emulator`); see README.
      tvAndroid = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "34" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeCmake = false;
        includeNDK = false;
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "android-tv" ];
        abiVersions = [ "x86" ];
      };
    in {
      devShells.${system} = {
        default = pkgs.mkShell {
          packages = [ pkgs.jdk21 android.platform-tools ];
          JAVA_HOME = pkgs.jdk21.home;
          ANDROID_HOME = sdk;
          ANDROID_SDK_ROOT = sdk;
          # AGP's Maven aapt2 is a generic-Linux binary that needs nix-ld; use the SDK's, which nixpkgs patches.
          # AGP calls that option experimental, hence the warning suppression.
          GRADLE_OPTS = builtins.concatStringsSep " " [
            "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2"
            "-Dorg.gradle.project.android.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE"
          ];
        };

        emulator = pkgs.mkShell {
          packages = [ pkgs.jdk21 tvAndroid.platform-tools ];
          JAVA_HOME = pkgs.jdk21.home;
          ANDROID_HOME = "${tvAndroid.androidsdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${tvAndroid.androidsdk}/libexec/android-sdk";
        };
      };
    };
}
