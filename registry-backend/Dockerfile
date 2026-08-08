# Runtime-only image (see README.md/../PLAN.md: the tinox compiler
# toolchain isn't containerizable here, this machine's checkout is ahead
# of both the public release and origin/main -- build the binary on the
# host first, this Dockerfile just packages it):
#
#   cd src && tinox build PackageController.tnx -o ../registry-backend
#   docker build -t tinox-central-registry-backend .
#
# `FROM scratch` for the final image: the compiled binary's only runtime
# deps are glibc/gc/openssl/zlib/brotli/zstd (confirmed via `ldd` on a
# local build, see paths below) -- no shell, package manager or anything
# else is needed at runtime, so there's no reason to ship a whole distro
# for a single statically-linkable-except-for-these-six-.so's binary.
# The `libs` stage exists purely to extract those exact files from the
# same distro (Arch) the binary was linked against, to avoid a glibc
# symbol-version mismatch against e.g. Debian's older glibc.

FROM archlinux:base AS libs
RUN pacman -Sy --noconfirm --needed gc openssl zlib brotli zstd \
    && install -d -o 65532 -g 65532 /data-empty

FROM scratch

COPY --from=libs /usr/lib64/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2
COPY --from=libs \
    /usr/lib/libc.so.6 \
    /usr/lib/libm.so.6 \
    /usr/lib/libgc.so.1 \
    /usr/lib/libz.so.1 \
    /usr/lib/libssl.so.3 \
    /usr/lib/libcrypto.so.3 \
    /usr/lib/libbrotlienc.so.1 \
    /usr/lib/libbrotlidec.so.1 \
    /usr/lib/libbrotlicommon.so.1 \
    /usr/lib/libzstd.so.1 \
    /usr/lib/
COPY --from=libs --chown=65532:65532 /data-empty /data

COPY registry-backend /usr/local/bin/registry-backend

ENV TINOX_CENTRAL_DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8080

USER 65532:65532
WORKDIR /data
ENTRYPOINT ["/usr/local/bin/registry-backend"]
