#!/usr/bin/env perl
# {{label}}: a Perl program.
use strict;
use warnings;

my %count;
$count{$_}++ for split /\s+/, "the cat and the hat and the bat";

print "Hello from {{label}} on Perl $^V\n";
printf "%-4s %d\n", $_, $count{$_} for sort keys %count;
