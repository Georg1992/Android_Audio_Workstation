#!/bin/bash
echo "🧹 Cleaning previous builds..."
./gradlew clean

echo "🔨 Building with native code..."
./gradlew assembleDebug

echo "✅ Build complete - native code should be updated!"

