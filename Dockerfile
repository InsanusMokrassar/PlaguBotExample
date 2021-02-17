FROM bellsoft/liberica-openjdk-alpine:19

ENTRYPOINT /bot/bin/bot /config.json

RUN mkdir /data/ && chown -R 1000:1000 /data/
VOLUME /data/

ADD --chown=1000:1000 build/distributions/bot.tar /

USER 1000
