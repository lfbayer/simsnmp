# simsnmp

[![][Build Status img]][Build Status]
[![][license img]][license]
[![][Maven Central img]][Maven Central]
[![][Javadocs img]][Javadocs]

A simple SNMP server that listens on multiple IPs and can respond to SNMP requests differently for each IP. Useful for testing applications that query SNMP devices.

# Overview

These tools were primarily developed to support development and testing of LogicVein's Net LineDancer NCM product. But they might be generic enough to be adapted to other scenarios.

There are two main parts of the tool. The CLI login shell, and the SNMP daemon.

## CLI
The CLI login shell is a perl script that can be configured as a user's linux login shell. So when a user logs into to the system (ie: ssh) they will immediately drop into a shell provided by this script. But instead of providing a normal shell, the user will just be presented with an interface that looks like a device. This is done by having recording log files of real device interactions. In the case of NetLD, we use the `logman` tool to convert NetLD adapter log files into a plain session log that can be used. But a log file can also be created by simply copy and pasting the output from any SSH session.

## SNMP

The SNMP daemon is a java server that listens for SNMP requests and responds with data found in an snmp file based on the IP address. The snmp file is simply the normal output from a `snmpwalk ` command against `system`.

The primary purpose in creating the SNMP daemon was to allow for Net LineDancer to detect which device type will be served for a given IP address based (for "Discovery"). The CLI login shell will function without the SNMP daemon running, so the SNMP daemon is only required if you want to support auto discovery features. But it is certainly highly recommended.

## How it works

Both of these tools work based on the local IP address of the incoming requests.  All incoming connections to the simulator have a destination IP which is a local IP address on the simulator host. The host can be configured to have multiple ip addresses (using normal linux ip configuration). This IP address is used to find an appropriate log file on the filesystem to play back, allowing each IP address to represent a different device.

For example, if the local IP address is 10.128.0.1, then you would have two files in `/home/lvi`: `10.128.0.1.log` for the CLI simulation and `10.128.0.1.snmp` for the SNMP simulation.

For SNMP, if there is no file matching the local IP address, then then SNMP daemon will fall back to the data defined in `default.snmp`. There is no default fallback for CLI connections.

# Tools

## logman

The logman script can be used for converting LogicVein "Net LineDancer" adapter log files into a standard session dump that can be used as a recording for the login-shell.pl script

# Build

## Build Prerequisites

* Java 8+
* Maven

## Build the SNMP daemon

Run maven to create the `simsnmp-all.jar`:
```
mvn assembly:assembly
```

This will create `simsnmp-all.jar` in the `target/` folder.

# Installation

## Dependencies
* systemd (SysV can work too, but you will have to write your own service script)
* SSH server
* Java 8+
* perl 5
    * File::Slurp
    * Term::ReadKey
    * Data::Dumper

To install with yum on centos:

Perl
```bash
yum install perl perl-File-Slurp perl-TermReadKey perl-Data-Dumper
```

Java (any java above 8 should be fine as long as it contains nashorn)
```
yum install java-1.8.0-openjdk-headless
```

## Create the user

Create a user with login-shell.pl configured as the login shell
```bash
useradd -s /home/lvi/login-shell.pl lvi
echo 'lvi:password' | chpasswd
```

## Create the folder structure

The code has paths all hard coded to use /home/lvi as the user home directory and working directory of the server.

Copy the following files into the `/home/lvi/` folder
* agent.js
* agent.conf
* login-shell.pl
* setup_ips.pl
* simsnmp-all.jar (see "Build the SNMP daemon" above)

Copy `simsnmp.service` to `/etc/systemd/system/multi-user.target.wants/`
(Assuming systemd)
`
## Routing

In order for the simulator to work to simulate more than one device, the single linux server must be configured with multi IP addresses. For this to work and allow access from other systems on the network the network infrastructure must be configured to route traffic to the simulator.

The setup_ips.pl script in this repository uses addresses within the 10.128.0.0/16 range. So to support this a static route must be added to the network that sends all 10.128.0.0/16 traffic to the simulator host's primary IP address. Depending on your network architecture you might want to change which subnets you use. Feel free to modify the setup_ips.pl script as you see fit.

### setup_ips.pl

This is a simple script that creates a bunch of IP addresses on eth0.

It will create all IP addresses in the range of 10.128.0.1 to 10.128.2.254. Modify this file to match the interface and IP address scheme you would like to use.

# Starting

No action is required to start the login shell, just log in with the created user and it should drop you in a shell.

The SNMP daemon and setup_ips.pl script will both be run when the simsnmp.service systemd service unit is started. `systemctl start simsnmp`

Note: the SNMP daemon must be restarted after any new IP addresses are added to the system, as currently it doesn't listen on INADDR_ANY, but instead iterates over the available IPs and listens on each one individually on startup. So new IPs won't be listening unless the service is restarted.

# Configuring SSH

There should be no special configuration necessary for SSH to work. So long as there is a user with /home/lvi/login-shell.pl configured as the login shell, the login will work.

# Configuring Telnet support

Telnet support can be added by simply installing telnet-server (in.telnetd)

# Configuring FTP support

Incoming FTP can also be supported by configuring a FTP server like vsftpd...

[Build Status]:https://travis-ci.org/lfbayer/simsnmp
[Build Status img]:https://travis-ci.org/lfbayer/simsnmp.svg?branch=master

[license]:LICENSE
[license img]:https://img.shields.io/badge/license-Apache%202-blue.svg
   
[Maven Central]:https://maven-badges.herokuapp.com/maven-central/com.lbayer/simsnmp
[Maven Central img]:https://maven-badges.herokuapp.com/maven-central/com.lbayer/simsnmp/badge.svg
   
[Javadocs]:http://javadoc.io/doc/com.lbayer/simsnmp
[Javadocs img]:http://javadoc.io/badge/com.lbayer/simsnmp.svg
 