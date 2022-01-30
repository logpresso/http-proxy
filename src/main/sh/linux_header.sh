#!/bin/sh
#
# chkconfig: 2345 99 20
#
# ----------------------------------------------------------------------------
#  Copyright 2001-2006 The Apache Software Foundation.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
# ----------------------------------------------------------------------------
#
#   Copyright (c) 2001-2006 The Apache Software Foundation.  All rights
#   reserved.

# resolve links - $0 may be a softlink
PRG="$0"

while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done

PRGDIR=`dirname "$PRG"`

# If a specific java binary isn't specified search for the standard 'java' binary
if [ -z "$JAVA" ] ; then
  # first, try built-in JRE
  if [ -x "$PRGDIR/jre/bin/java" ] ; then
    JAVA="$PRGDIR/jre/bin/java"
  elif [ -n "$JAVA_HOME"  ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
      # IBM's JDK on AIX uses strange locations for the executables
      JAVA="$JAVA_HOME/jre/sh/java"
    else
      JAVA="$JAVA_HOME/bin/java"
    fi
  else
    # or use PATH
    JAVA=`command -v java`
  fi
fi

if [ ! -x "$JAVA" ] ; then
  echo "Error: JAVA_HOME is not defined correctly." 1>&2
  echo "  We cannot execute $JAVA" 1>&2
  exit 1
fi

exec $JAVA -jar $0 "$@"

#### jar will be attached after following blank lines
#### following blank lines are intentional.
















