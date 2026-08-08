# Runtime-only image (see README.md/../PLAN.md: the tinox compiler
# toolchain isn't containerizable here, this machine's checkout is ahead
# of both the public release and origin/main -- build the binary on the
# host first, this Dockerfile just packages it):
#
#   cd src && tinox build PackageController.tnx -o ../registry-backend
#   docker build -t tinox-central-registry-backend .
#
# Runtime base: archlinux:base, the same distro the binary was linked
# against (avoids a glibc symbol-version mismatch a smaller/older distro's
# libc could hit) -- swapped from the earlier `FROM scratch` (single
# static-except-six-.so's binary, no shell at all) specifically so
# `docker exec -it <container> bash` works for debugging (`scratch` has
# no /bin/*, `docker exec`/nsenter -a land nowhere). Deliberate tradeoff:
# real base-image attack surface and a much bigger image now, in exchange
# for that.

FROM archlinux:base

RUN pacman -Sy --noconfirm --needed gc openssl zlib brotli zstd bash \
    && install -d -o 65532 -g 65532 /data

COPY registry-backend /usr/local/bin/registry-backend

ENV TINOX_CENTRAL_DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8080

USER 65532:65532
WORKDIR /data
ENTRYPOINT ["/usr/local/bin/registry-backend"]
