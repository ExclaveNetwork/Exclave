#!/bin/bash

CGO_LDFLAGS="-Wl,-z,max-page-size=16384" gomobile bind -v -androidapi 21 -tags="with_clash" "github.com/dyhkwong/libsagernetcore" || exit 1

proj=../../app/libs
if [ -d $proj ]; then
  cp -vf libsagernetcore.aar $proj
fi
