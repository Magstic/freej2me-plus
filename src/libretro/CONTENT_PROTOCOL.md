# Libretro content protocol

The libretro core and Java worker exchange game content over the worker's
standard-input pipe. With the v1 event, the Java process never receives the
frontend's host file path.

## Version 1 content event

All integer fields use unsigned big-endian byte order.

| Field | Size | Meaning |
| --- | ---: | --- |
| Event type | 1 byte | `0x10` (content protocol v1) |
| Content size | 4 bytes | JAR/KJX byte count, from 1 byte through 256 MiB |
| Content | variable | Exact JAR/KJX bytes |

The core owns a copy of the content for the entire loaded-game lifetime. This
is required because libretro only guarantees `retro_game_info.data` during
`retro_load_game()` unless persistent-data support is negotiated.

The event type identifies the protocol version. The Java worker validates the
declared size and detects the content type from its signature, hashes the
stream with SHA-256, and materializes it under
`freej2me_system/content_cache/<sha256>.jar` (or `.kjx`). That internal path is
ASCII-only and content-addressed, so host path encoding and path separator
rules do not reach `URLClassLoader` or `JarFile`. Cache entries are validated
against their SHA-256 digest and intentionally retained across launches.

The Java worker accepts only the `0x10` content event.
