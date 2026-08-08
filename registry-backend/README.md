# registry-backend

REST API for the tinox-central package registry. Annotation-driven
(`@GET`/`@POST`/`@Path`/`@Auth`/`@ApplicationComponent`/`@Inject`) — the
compiler auto-generates `main()`, so there is no `Main.tnx`. The entry
point to compile/run is `PackageController.tnx` (its import graph pulls
in `PackageStore`/`AuthValidator`/the DTO classes).

## Running

```bash
cd src
TINOX_CENTRAL_DATA_DIR=/path/to/data \
TINOX_CENTRAL_ADMIN_KEY=your-admin-key \
tinox run PackageController.tnx
```

Optional: `TINOX_PORT` (default `8080`).

## Building a standalone binary

```bash
cd src
tinox build PackageController.tnx -o registry-backend
TINOX_CENTRAL_DATA_DIR=/path/to/data TINOX_CENTRAL_ADMIN_KEY=your-admin-key ./registry-backend
```

## Tests

```bash
tests/e2e/smoke_test.sh
```

See `../PLAN.md` for the full design/architecture writeup.
