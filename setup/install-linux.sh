#!/bin/sh
# Stremio Lean for Linux: installs the app for your user and adds a `stremio-lean` command.
# Needs Java 17+ and mpv (Arch: sudo pacman -S jre-openjdk mpv).
set -e
dir="$HOME/.local/share/stremio-lean"
bin="$HOME/.local/bin"
mkdir -p "$dir" "$bin"
curl -fL -o "$dir/stremio-lean-linux.jar" https://github.com/shaz-arch/stremio-lite-/releases/latest/download/stremio-lean-linux.jar
cat > "$bin/stremio-lean" <<'SH'
#!/bin/sh
exec java -jar "$HOME/.local/share/stremio-lean/stremio-lean-linux.jar" "$@"
SH
chmod +x "$bin/stremio-lean"
command -v java >/dev/null || echo "Java isn't installed: sudo pacman -S jre-openjdk"
command -v mpv  >/dev/null || echo "mpv isn't installed: sudo pacman -S mpv"
echo "Installed. Run: stremio-lean   (make sure ~/.local/bin is on your PATH)"
