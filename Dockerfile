FROM maven:3-jdk-8
LABEL maintainer="BaseX Team <basex-talk@mailman.uni-konstanz.de>"

# Compile BaseX, install
COPY . /usr/src/basex/

# install git as "buildnumber-maven-plugin" requires git:
RUN apt-get update && \
    apt-get install -y git && \
    apt-get -y autoremove && \
    rm -r /var/lib/apt/lists/* /var/cache/* && \
    cd /usr/src/basex && \
    mvn clean install -DskipTests && \
    ln -s /usr/src/basex/basex-*/etc/* /usr/local/bin &&\
    yes "" | adduser --home /srv --disabled-password --disabled-login --uid 1984 basex && \
    mkdir -p /srv/.m2 /srv/basex/data /srv/basex/repo /srv/basex/webapp && \
    cp -r /usr/src/basex/basex-api/src/main/webapp/WEB-INF /srv/basex/webapp && \
    chown -R basex /srv
    
USER basex
ENV MAVEN_CONFIG=/srv/.m2

# 1984/tcp: API
# 8984/tcp: HTTP
# 8985/tcp: HTTP stop
EXPOSE 1984 8984 8985

#
# At HG we do not want to use these volumes
#
#VOLUME ["/srv/basex/data", "/srv/basex/repo","/srv/basex/webapp"]

WORKDIR /srv

# Run BaseX HTTP server by default
CMD ["/usr/local/bin/basexhttp"]
