# Build context is this directory only; never send server config to a builder.
# Supply an Alpine runtime pinned by digest after the owner's image review.
ARG RUNTIME_BASE
FROM ${RUNTIME_BASE}
RUN apk add --no-cache ca-certificates curl \
    && addgroup -g 10001 personal \
    && adduser -D -H -u 10001 -G personal personal
ARG NODE_SHA256
COPY --chmod=0555 bin/rga-personal-node /usr/local/bin/rga-personal-node
RUN test "${#NODE_SHA256}" -eq 64 \
    && printf '%s  %s\n' "$NODE_SHA256" /usr/local/bin/rga-personal-node | sha256sum -c -
USER 10001:10001
WORKDIR /tmp
ENV RGA_PERSONAL_NODE_CONFIG=/run/personal/node.json
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/rga-personal-node"]
