#!/bin/bash
# Push the FLUX.2-Klein model to the Android device.
# Run this once before launching the demo app.
#
# Usage: ./push_model.sh [path/to/model_dir]
#   Default model dir: ../FLUX.2-klein-4B-MNN-int8

set -e

MODEL_DIR="${1:-../FLUX.2-klein-4B-MNN-int8}"
DEVICE_PATH="/sdcard/mnn_flux/model"

if [ ! -d "$MODEL_DIR" ]; then
    echo "ERROR: Model directory not found: $MODEL_DIR"
    echo "Pass the correct path as the first argument."
    exit 1
fi

echo "Pushing model from $MODEL_DIR to device:$DEVICE_PATH ..."

adb shell mkdir -p "$DEVICE_PATH"
adb push "$MODEL_DIR/." "$DEVICE_PATH"

echo ""
echo "Done! Model files on device:"
adb shell ls -lh "$DEVICE_PATH"
