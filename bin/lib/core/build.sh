#!/usr/bin/env bash

source "bin/init/env.sh"

rm -rf library/core/build
cd library/core
./build.sh || exit 1

mkdir -p "$PROJECT/app/libs"
cp -f libsagernetcore.aar "$PROJECT/app/libs"
