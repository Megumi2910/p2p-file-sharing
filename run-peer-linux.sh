#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Resolve peer-app.jar location
if [ -f "$SCRIPT_DIR/peer-app.jar" ]; then
    JAR="$SCRIPT_DIR/peer-app.jar"
elif [ -f "$SCRIPT_DIR/peer-app/target/peer-app.jar" ]; then
    JAR="$SCRIPT_DIR/peer-app/target/peer-app.jar"
elif [ -f "./peer-app.jar" ]; then
    JAR="./peer-app.jar"
elif [ -f "./peer-app/target/peer-app.jar" ]; then
    JAR="./peer-app/target/peer-app.jar"
else
    echo "Error: peer-app.jar not found." >&2
    echo "Run 'mvn clean package' to build it, or place peer-app.jar beside this script." >&2
    exit 1
fi

# Resolve configuration file (defaults to peer.properties or argument $1)
CONFIG="${1:-peer.properties}"
if [ ! -f "$CONFIG" ] && [ -f "$SCRIPT_DIR/$CONFIG" ]; then
    CONFIG="$SCRIPT_DIR/$CONFIG"
fi

echo "Launching: java -jar \"$JAR\" \"$CONFIG\""
exec java -jar "$JAR" "$CONFIG"
