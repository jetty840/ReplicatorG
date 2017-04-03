#!/bin/sh

REVISION=`head -n 1 changelog.txt | cut -f 1 -d " "`

rm -rf dist
JAVA_HOME=$(/usr/libexec/java_home -v 1.6) ant clean
JAVA_HOME=$(/usr/libexec/java_home -v 1.6) ant \
  -Dreplicatorg.version=$REVISION dist-linux
#ant -Dreplicatorg.version=$REVISION dist-linux64
