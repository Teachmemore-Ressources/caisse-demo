#!/bin/bash
H="$HOME/supervision-test/glpi-agent"; P5="$HOME/supervision-test/perl5"
export LD_LIBRARY_PATH="$P5/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export PERL5LIB="$H/usr/share/glpi-agent/lib:$P5/usr/share/perl5:$P5/usr/lib/x86_64-linux-gnu/perl5/5.40:$P5/usr/lib/x86_64-linux-gnu/perl/5.40"
perl -e 'use GLPI::Agent::Protocol::Inventory; print "ok\n"' 2>&1 | head -5
