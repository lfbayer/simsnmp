FROM prasadlvi/openjdk-11-jre AS simulator

LABEL maintainer="lfbayer@gmail.com"

RUN yum install -y openssh-server
RUN yum install -y perl
RUN yum install -y perl-File-Slurp
RUN yum install -y perl-TermReadKey
RUN yum install -y perl-Data-Dumper
RUN yum install -y initscripts

CMD bash
