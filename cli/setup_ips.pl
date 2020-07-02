#!/usr/bin/perl

use strict;
use warnings;

die "Must be run as root\n" if ($<);

foreach my $c (0 .. 2)
{
    foreach my $d (1 .. 254)
    {
        my $command = "ip addr add 10.128.$c.$d/24 dev eth0";
        print $command . "\n";
        print `$command`;
    }
}
