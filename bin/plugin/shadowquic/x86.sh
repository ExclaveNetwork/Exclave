#!/bin/bash

source "bin/init/env.sh"
source "bin/plugin/shadowquic/init.sh"

DIR="$ROOT/x86"
mkdir -p $DIR

export CC=$ANDROID_X86_CC
export CXX=$ANDROID_X86_CXX
export RUST_ANDROID_GRADLE_CC=$ANDROID_X86_CC
export CARGO_TARGET_I686_LINUX_ANDROID_LINKER=$PROJECT/bin/rust-linker/linker-wrapper.sh
export RUST_ANDROID_GRADLE_CC_LINK_ARG="-Wl,-z,max-page-size=16384"

cargo build --release -p shadowquic --target i686-linux-android
cp target/i686-linux-android/release/shadowquic $DIR/$LIB_OUTPUT
