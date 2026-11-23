#!/bin/sh
# Stremonino for Linux: installs the app for your user and adds a `stremonino` command.
# Needs Java 17+ and mpv (Arch: sudo pacman -S jre-openjdk mpv).
set -e
dir="$HOME/.local/share/stremonino"
bin="$HOME/.local/bin"
mkdir -p "$dir" "$bin"
curl -fL -o "$dir/stremonino-linux.jar" https://github.com/shaz-arch/stremio-lite-/releases/latest/download/stremonino-linux.jar
cat > "$bin/stremonino" <<'SH'
#!/bin/sh
exec java -jar "$HOME/.local/share/stremonino/stremonino-linux.jar" "$@"
SH
chmod +x "$bin/stremonino"
command -v java >/dev/null || echo "Java isn't installed: sudo pacman -S jre-openjdk"
command -v mpv  >/dev/null || echo "mpv isn't installed: sudo pacman -S mpv"
echo "Installed. Run: stremonino   (make sure ~/.local/bin is on your PATH)"
