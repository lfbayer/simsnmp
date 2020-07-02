#!/usr/bin/perl

use strict;
use warnings;

use File::Slurp;
use Term::ReadLine;
use Term::ReadKey;
use Time::Stamp qw(localstamp);
use Data::Dumper;

$Term::ReadLine::termcap_nowarn = 1;

my $debug = 1;

my $log;
if ($debug)
{
    open($log, '>>', 'shell.log') or die $!;

    my $old = select $log;
    $| = 1;
    select $old;
}

my $remote_ip = shift @ARGV;
if (defined($remote_ip) && $remote_ip eq '-c')
{
    my $cmd = shift @ARGV;
    print $log "Executing command: ", $cmd, "\n";
    system $cmd;
    exit 0;
}

unless ($remote_ip)
{
    if ($ENV{'SSH_CONNECTION'} && $ENV{'SSH_CONNECTION'} =~ /^\S+\s+\S+\s+(\S+)/)
    {
	$remote_ip = $1;
    }
    elsif ($ENV{'REMOTEHOST'} && $ENV{'REMOTEHOST'} =~ /^::ffff:(\S+)|lvi\.co\.jp$/)
    {
	$remote_ip = _get_local_ip_for_telnet();
    }
    else
    {
	print $log "No SSH_CONNECTION!\n";
	print "No Connection!\n";
	print Dumper(%ENV) . "\n";
	exit 2;
    }
}

my $file = "$remote_ip.log";
unless (-f $file)
{
    die ("No file for IP: $remote_ip\n");
}

my $find_prompt_regex = '^(.+(?:[#>]|#>) *)';

my $prompt = 'No-Prompt#';

my $sim_vars;

my $before_prompt = '';
open(my $fd, '<', $file) or die "Unable to open file: $file";
while (<$fd>)
{
    if (/\@SIMULATOR: SET ([a-zA-Z]+)=(.+)\@/)
    {
	$sim_vars->{$1} = $2;
	next;
    }

    if (/$find_prompt_regex/)
    {
	next if (/\<WARNING\>/);

	$prompt = $1;
	last;
    }
    elsif (/(.+)\@SIMULATOR: PROMPT\@/)
    {
	$prompt = $1;
	last;
    }

    _handle_line($_);
}

close($fd);

my $term = Term::ReadLine->new('faux-device');
$term->ornaments(0);

my $password_prompt;

my $newline_strategy = 0;
if ($sim_vars->{'NEWLINES'})
{
    my $strategy = $sim_vars->{'NEWLINES'};
    if (!defined($strategy) || $strategy =~ /STRIP/)
    {
	$newline_strategy = 0;
    }
    elsif ($strategy =~ /PRESERVE/)
    {
	$newline_strategy = 1;
    }
    elsif ($strategy =~ /RANDOM/)
    {
	$newline_strategy = 2;
    }
    elsif ($strategy =~ /(CR|LF)+/)
    {
	$newline_strategy = $strategy;
	print $log "Newline Strategy: $strategy\n";
	$newline_strategy =~ s/CR/\r/g;
	$newline_strategy =~ s/LF/\n/g;
	print $log "Newline Strategy: " . unpack("H*", $newline_strategy) . "\n";
    }
}

my $echo_def = $sim_vars->{'ECHO'};
if (defined($echo_def))
{
    ReadMode('noecho');
}

my $cmd;
while (defined($cmd = $term->readline(defined($password_prompt) ? undef : $prompt)))
{
    if (defined($echo_def))
    {
	print STDERR $echo_def;
	print STDERR $cmd;
	print STDERR "\n";
    }

    my $prompt_regex = quotemeta($prompt) . '\s*';
    if ($cmd =~ /^\s*$/)
    {
	print "\n";
	next;
    }

    if ($cmd eq '!list')
    {
	open(my $fd, '<', $file) or die;
	while (<$fd>)
	{
	    if (/^$prompt_regex(.+)/)
	    {
		print STDERR $1 . "\n";
	    }
	}
	close($fd);

	next;
    }

    print $log localstamp() . ": $cmd\n" if ($log);
    my $regex;
    if (defined($password_prompt))
    {
	$regex = quotemeta($password_prompt);
	ReadMode('restore') unless (defined($echo_def));
	$password_prompt = undef;
	print STDERR "\n";
    }
    else
    {
	$regex = $prompt_regex . quotemeta($cmd);
    }

    my $handled = 0;

    open($fd, '<', $file) or die;
outer:
    while (<$fd>)
    {
	chomp;
        next unless (/^$regex\s*$/);

	print $log localstamp() . ": match found\n" if ($log);
	while (<$fd>)
	{
	    if (/^$prompt_regex/)
	    {
		$handled = 1;
		last outer;
	    }

	    if ($newline_strategy eq 0)
	    {
		s/\r\n/\n/;
	    }
	    elsif ($newline_strategy eq 1)
	    {
	    }
	    elsif ($newline_strategy eq 2)
	    {
		if (rand(10) > 5)
		{
		    s/\r\n/\n/;
		}
	    }
	    else
	    {
		s/[\r\n]+/$newline_strategy/;
	    }

#	    s/\n/\r\n/;
	    if (/^\s*[Pp]assword:\s*$/)
	    {
		chomp;
		$password_prompt = $_;
		print STDERR;
		ReadMode('noecho');
		$handled = 1;
		last outer;
	    }

	    if (/(.+)\@SIMULATOR: PROMPT\@/)
	    {
		$prompt = $1;
		$handled = 1;
		last outer;
	    }

	    _handle_line($_)
	}
    }

    close($fd);
}

close($log) if ($log);

sub _handle_line
{
    my ($line) = @_;

    if (/\@SIMULATOR: CMD (.+)\@/)
    {
	my $pre = $`;
	my $command = $1;
	my $post = $'; # ' # comment to fix emacs syntax highlighting, sorry.

	print STDERR $pre;
	eval $command;
	if ($@)
	{
	    print STDERR "SIMULATOR ERROR: $@\n";
	}
	
	print STDERR $post;
    }
    else
    {
	print STDERR;
    }
}

sub _get_local_ip_for_telnet
{
    my $pid = $$;
    while (1)
    {
	my $stat = `cat /proc/$pid/stat`;
	die("invalid stat: $stat") unless ($stat =~ /^\d+\s+\(([^\)]+)\)\s+\S+\s+(\d+)/);
	my $procname = $1;
	my $ppid = $2;

	if ($procname eq 'in.telnetd')
	{
	    last;
	}

	$pid = $ppid;
    }

    my $connections = `sudo /usr/sbin/lsof -a -p $pid -i :23 -n -P`;
    die("no connection found: $connections") unless ($connections =~ /\s([\d\.]+):23->/);
    return $1;
}

