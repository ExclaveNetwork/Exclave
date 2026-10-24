#!/usr/bin/env bash

source "bin/init/env.sh"

export AR=$ANDROID_AR
export LD=$ANDROID_LD

ndkVer=$(grep Pkg.Revision $ANDROID_NDK_HOME/source.properties)
ndkVer=${ndkVer#*= }
ndkVer=${ndkVer%%.*}

export CARGO_NDK_MAJOR_VERSION=$ndkVer
export RUST_ANDROID_GRADLE_PYTHON_COMMAND=python
export RUST_ANDROID_GRADLE_LINKER_WRAPPER_PY=$PROJECT/bin/rust-linker/linker-wrapper.py
export RUST_ANDROID_GRADLE_CC_LINK_ARG=""
export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"
export RUSTFLAGS="--remap-path-prefix=${rootDir}=. --remap-path-prefix=${CARGO_HOME}=.cargo -C link-args=-Wl,--build-id=none -C strip=symbols"

CURR="plugin/shadowquic"
CURR_PATH="$PROJECT/$CURR"

ROOT="$CURR_PATH/src/main/jniLibs"
OUTPUT="shadowquic"
LIB_OUTPUT="lib$OUTPUT.so"

cd $CURR_PATH/src/main/rust/shadowquic
