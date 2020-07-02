#!/usr/bin/env bash

# initialize keys for SSH server
/usr/sbin/sshd-keygen

# Start ssh server
/usr/sbin/sshd

useradd -s /home/lvi/login-shell.pl lvi
echo 'lvi:lvi' | chpasswd

cd /home/lvi
perl /home/lvi/setup_ips.pl

cp /home/lvi/default.snmp /home/lvi/tmp/
chmod -R ag+w /home/lvi/tmp

# make all the files writable so they can be deleted by jenkins
chmod -R a+w /home/lvi/tmp

cd /home/lvi/tmp
java -jar /home/lvi/simsnmp-all.jar -c /home/lvi/agent.conf
