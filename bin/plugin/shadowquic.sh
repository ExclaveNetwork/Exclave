#!/usr/bin/env bash

bin/plugin/shadowquic/init.sh &&
  bin/plugin/shadowquic/armeabi-v7a.sh &&
  bin/plugin/shadowquic/arm64-v8a.sh &&
  bin/plugin/shadowquic/x86.sh &&
  bin/plugin/shadowquic/x86_64.sh
