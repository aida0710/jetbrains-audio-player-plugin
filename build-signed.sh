#!/bin/bash
set -e

echo "=== Audio Player Plugin - Signed Build ==="

if [ -z "$CERTIFICATE_CHAIN" ] && [ ! -f "chain.crt" ]; then
    echo "Error: CERTIFICATE_CHAIN env var or chain.crt file required"
    exit 1
fi

if [ -z "$PRIVATE_KEY" ] && [ ! -f "private.pem" ]; then
    echo "Error: PRIVATE_KEY env var or private.pem file required"
    exit 1
fi

./gradlew clean signPlugin

echo ""
echo "Signed build complete."
echo "Output: build/distributions/"
ls -lh build/distributions/ 2>/dev/null || echo "(no distribution found)"
