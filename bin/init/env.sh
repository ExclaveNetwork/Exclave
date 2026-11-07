#!/bin/bash

if [[ "$OSTYPE" =~ ^darwin ]]; then
  export PROJECT=$PWD
else
  export PROJECT=$(realpath .)
fi
