FROM rust:1.95-bookworm AS builder
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && printf 'fn main() {}' > src/main.rs && cargo build --release && rm -rf src
COPY src ./src
RUN cargo build --release

FROM debian:bookworm-slim
RUN useradd --system --uid 10001 --create-home hiddi
RUN mkdir -p /var/lib/hiddi && chown hiddi:hiddi /var/lib/hiddi
COPY --from=builder /app/target/release/hiddi-server /usr/local/bin/hiddi-server
USER hiddi
EXPOSE 3000
ENV HIDDI_BIND_ADDR=0.0.0.0:3000
ENV HIDDI_DATABASE_PATH=/var/lib/hiddi/hiddi.db
VOLUME ["/var/lib/hiddi"]
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD ["/usr/bin/env", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/3000; printf 'GET /health HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3; grep -q '200 OK' <&3"]
CMD ["hiddi-server"]
